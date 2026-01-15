package libbox

import (
	"context"
	"io"
	"log"
	"net"
	"net/http"
	"time"

	"github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

// ============================================================================
// KunBox Fetch Extension - 内核级 HTTP 请求
// 通过指定的 outbound 发起 HTTP 请求，支持所有代理协议
// Version: 1.0.0
// Compatible with: sing-box v1.10.0 - v1.12.x
//
// 依赖的官方包 (稳定):
//   - github.com/sagernet/sing/common/metadata
//   - github.com/sagernet/sing/common/network
//   - github.com/sagernet/sing-box/adapter
// ============================================================================

// FetchResult holds the result of an HTTP fetch
// Note: gomobile automatically generates getter methods for exported fields,
// so we don't need to define GetStatusCode/GetBody/GetError manually.
type FetchResult struct {
	StatusCode int32
	Body       string
	Error      string
}

// IsSuccess returns true if the fetch was successful
func (r *FetchResult) IsSuccess() bool {
	return r != nil && r.Error == "" && r.StatusCode >= 200 && r.StatusCode < 300
}

// FetchURL fetches a URL using the running service's outbound
// outboundTag: the outbound to use (e.g., "proxy", "direct")
// url: the URL to fetch
// timeoutMs: timeout in milliseconds
// Returns FetchResult with status code, body, or error
func (s *BoxService) FetchURL(outboundTag string, url string, timeoutMs int32) *FetchResult {
	if s == nil || s.instance == nil {
		log.Println("[KunBox Fetch] Service not available")
		return &FetchResult{Error: "Service not available"}
	}

	outbound, loaded := s.instance.Outbound().Outbound(outboundTag)
	if !loaded {
		log.Printf("[KunBox Fetch] Outbound not found: %s", outboundTag)
		return &FetchResult{Error: "Outbound not found: " + outboundTag}
	}

	dialer, ok := outbound.(N.Dialer)
	if !ok {
		log.Printf("[KunBox Fetch] Outbound is not a dialer: %s", outboundTag)
		return &FetchResult{Error: "Outbound is not a dialer"}
	}

	ctx, cancel := context.WithTimeout(s.ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	return doFetch(ctx, dialer, url, timeoutMs)
}

// FetchURLWithHeaders fetches a URL with custom headers
func (s *BoxService) FetchURLWithHeaders(outboundTag string, url string, headers string, timeoutMs int32) *FetchResult {
	if s == nil || s.instance == nil {
		log.Println("[KunBox Fetch] Service not available")
		return &FetchResult{Error: "Service not available"}
	}

	outbound, loaded := s.instance.Outbound().Outbound(outboundTag)
	if !loaded {
		return &FetchResult{Error: "Outbound not found: " + outboundTag}
	}

	dialer, ok := outbound.(N.Dialer)
	if !ok {
		return &FetchResult{Error: "Outbound is not a dialer"}
	}

	ctx, cancel := context.WithTimeout(s.ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	return doFetchWithHeaders(ctx, dialer, url, headers, timeoutMs)
}

// FetchURLStandalone fetches a URL by creating a temporary instance
// Use this when VPN is NOT running
// configContent: full sing-box config JSON
// outboundTag: the outbound to use
// url: the URL to fetch
// timeoutMs: timeout in milliseconds
func FetchURLStandalone(configContent string, outboundTag string, url string, timeoutMs int32) *FetchResult {
	if configContent == "" || outboundTag == "" || url == "" {
		log.Println("[KunBox Fetch] Invalid parameters")
		return &FetchResult{Error: "Invalid parameters"}
	}

	ctx := BaseContext(nil)
	options, err := parseConfig(ctx, configContent)
	if err != nil {
		log.Printf("[KunBox Fetch] Config parse error: %v", err)
		return &FetchResult{Error: "Config parse error: " + err.Error()}
	}

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	// Use stub platform interface
	ctx = service.ContextWith[platform.Interface](ctx, (*platformInterfaceStub)(nil))

	// Create temporary box instance
	instance, err := box.New(box.Options{
		Context: ctx,
		Options: options,
	})
	if err != nil {
		log.Printf("[KunBox Fetch] Failed to create instance: %v", err)
		return &FetchResult{Error: "Failed to create instance: " + err.Error()}
	}
	defer instance.Close()

	// Start the instance
	if err := instance.Start(); err != nil {
		log.Printf("[KunBox Fetch] Failed to start instance: %v", err)
		return &FetchResult{Error: "Failed to start instance: " + err.Error()}
	}

	// Get the outbound
	outbound, loaded := instance.Outbound().Outbound(outboundTag)
	if !loaded {
		log.Printf("[KunBox Fetch] Outbound not found: %s", outboundTag)
		return &FetchResult{Error: "Outbound not found: " + outboundTag}
	}

	dialer, ok := outbound.(N.Dialer)
	if !ok {
		log.Printf("[KunBox Fetch] Outbound is not a dialer: %s", outboundTag)
		return &FetchResult{Error: "Outbound is not a dialer"}
	}

	fetchCtx, fetchCancel := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer fetchCancel()

	result := doFetch(fetchCtx, dialer, url, timeoutMs)
	log.Printf("[KunBox Fetch] Standalone fetch completed: %s -> %d", url, result.StatusCode)
	return result
}

// doFetch performs the actual HTTP fetch
func doFetch(ctx context.Context, dialer N.Dialer, url string, timeoutMs int32) *FetchResult {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialer.DialContext(ctx, network, M.ParseSocksaddr(addr))
		},
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          10,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
		DisableKeepAlives:     false,
	}

	client := &http.Client{
		Transport: transport,
		Timeout:   time.Duration(timeoutMs) * time.Millisecond,
	}

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		log.Printf("[KunBox Fetch] Failed to create request: %v", err)
		return &FetchResult{Error: "Failed to create request: " + err.Error()}
	}

	// Set common headers
	req.Header.Set("User-Agent", "KunBox/1.0")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Accept-Encoding", "gzip, deflate")

	resp, err := client.Do(req)
	if err != nil {
		log.Printf("[KunBox Fetch] Request failed: %v", err)
		return &FetchResult{Error: "Request failed: " + err.Error()}
	}
	defer resp.Body.Close()

	// Limit response body size to 10MB
	limitedReader := io.LimitReader(resp.Body, 10*1024*1024)
	body, err := io.ReadAll(limitedReader)
	if err != nil {
		log.Printf("[KunBox Fetch] Failed to read body: %v", err)
		return &FetchResult{Error: "Failed to read body: " + err.Error()}
	}

	log.Printf("[KunBox Fetch] Success: %s -> %d (%d bytes)", url, resp.StatusCode, len(body))
	return &FetchResult{
		StatusCode: int32(resp.StatusCode),
		Body:       string(body),
		Error:      "",
	}
}

// doFetchWithHeaders performs HTTP fetch with custom headers
// headers format: "Key1:Value1\nKey2:Value2"
func doFetchWithHeaders(ctx context.Context, dialer N.Dialer, url string, headers string, timeoutMs int32) *FetchResult {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialer.DialContext(ctx, network, M.ParseSocksaddr(addr))
		},
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          10,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}

	client := &http.Client{
		Transport: transport,
		Timeout:   time.Duration(timeoutMs) * time.Millisecond,
	}

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return &FetchResult{Error: "Failed to create request: " + err.Error()}
	}

	// Parse and set custom headers
	if headers != "" {
		for _, line := range ParseTags(headers) {
			if idx := findColon(line); idx > 0 {
				key := line[:idx]
				value := line[idx+1:]
				req.Header.Set(key, value)
			}
		}
	}

	resp, err := client.Do(req)
	if err != nil {
		return &FetchResult{Error: "Request failed: " + err.Error()}
	}
	defer resp.Body.Close()

	limitedReader := io.LimitReader(resp.Body, 10*1024*1024)
	body, err := io.ReadAll(limitedReader)
	if err != nil {
		return &FetchResult{Error: "Failed to read body: " + err.Error()}
	}

	return &FetchResult{
		StatusCode: int32(resp.StatusCode),
		Body:       string(body),
		Error:      "",
	}
}

// findColon finds the first colon in a string
func findColon(s string) int {
	for i := 0; i < len(s); i++ {
		if s[i] == ':' {
			return i
		}
	}
	return -1
}
