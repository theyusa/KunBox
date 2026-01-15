package libbox

import (
	"context"
	"log"
	"time"

	"github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

// ============================================================================
// KunBox URLTest Extension - 内核级延迟测试
// 参考官方 sing-box urltest 实现，提供独立的测速 API
// Version: 1.1.0
// Compatible with: sing-box v1.10.0 - v1.12.x
//
// 依赖的官方包 (稳定):
//   - github.com/sagernet/sing-box/common/urltest
//   - github.com/sagernet/sing-box/adapter
//   - github.com/sagernet/sing/common/network
// ============================================================================

// URLTestResult holds the result of a URL test
type URLTestResult struct {
	Tag      string
	Delay    int32 // milliseconds, -1 = failed
	Error    string
}

// URLTestOutbound performs a latency test on a specific outbound using a running service
// This is the recommended method when VPN is active
// Returns delay in milliseconds, or -1 on failure
func (s *BoxService) URLTestOutbound(outboundTag string, url string, timeoutMs int32) int32 {
	if s == nil || s.instance == nil {
		log.Println("[KunBox URLTest] Service not available")
		return -1
	}

	outbound, loaded := s.instance.Outbound().Outbound(outboundTag)
	if !loaded {
		log.Printf("[KunBox URLTest] Outbound not found: %s", outboundTag)
		return -1
	}

	dialer, ok := outbound.(N.Dialer)
	if !ok {
		log.Printf("[KunBox URLTest] Outbound is not a dialer: %s", outboundTag)
		return -1
	}

	if url == "" {
		url = "https://www.gstatic.com/generate_204"
	}

	ctx, cancel := context.WithTimeout(s.ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	delay, err := urltest.URLTest(ctx, url, dialer)
	if err != nil {
		log.Printf("[KunBox URLTest] Test failed for %s: %v", outboundTag, err)
		return -1
	}

	// Store result in history for UI display
	if s.urlTestHistoryStorage != nil {
		s.urlTestHistoryStorage.StoreURLTestHistory(outboundTag, &adapter.URLTestHistory{
			Time:  time.Now(),
			Delay: delay,
		})
	}

	log.Printf("[KunBox URLTest] %s: %dms", outboundTag, delay)
	return int32(delay)
}

// URLTestStandalone performs a latency test by creating a temporary service
// Use this when VPN is NOT running
// configContent: full sing-box config JSON
// outboundTag: the outbound tag to test
// url: test URL (empty = default generate_204)
// timeoutMs: timeout in milliseconds
// Returns delay in milliseconds, or -1 on failure
func URLTestStandalone(configContent string, outboundTag string, url string, timeoutMs int32) int32 {
	if configContent == "" || outboundTag == "" {
		log.Println("[KunBox URLTest] Invalid parameters")
		return -1
	}

	ctx := BaseContext(nil)
	options, err := parseConfig(ctx, configContent)
	if err != nil {
		log.Printf("[KunBox URLTest] Config parse error: %v", err)
		return -1
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
		log.Printf("[KunBox URLTest] Failed to create instance: %v", err)
		return -1
	}
	defer instance.Close()

	// Start the instance
	if err := instance.Start(); err != nil {
		log.Printf("[KunBox URLTest] Failed to start instance: %v", err)
		return -1
	}
	defer instance.Close()

	// Get the outbound
	outbound, loaded := instance.Outbound().Outbound(outboundTag)
	if !loaded {
		log.Printf("[KunBox URLTest] Outbound not found: %s", outboundTag)
		return -1
	}

	dialer, ok := outbound.(N.Dialer)
	if !ok {
		log.Printf("[KunBox URLTest] Outbound is not a dialer: %s", outboundTag)
		return -1
	}

	if url == "" {
		url = "https://www.gstatic.com/generate_204"
	}

	testCtx, testCancel := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer testCancel()

	delay, err := urltest.URLTest(testCtx, url, dialer)
	if err != nil {
		log.Printf("[KunBox URLTest] Test failed for %s: %v", outboundTag, err)
		return -1
	}

	log.Printf("[KunBox URLTest] Standalone %s: %dms", outboundTag, delay)
	return int32(delay)
}

// BatchURLTestResult holds results for batch testing
type BatchURLTestResult struct {
	results []URLTestResult
}

// Len returns the number of results
func (r *BatchURLTestResult) Len() int {
	if r == nil {
		return 0
	}
	return len(r.results)
}

// Get returns the result at index
func (r *BatchURLTestResult) Get(index int) *URLTestResult {
	if r == nil || index < 0 || index >= len(r.results) {
		return nil
	}
	return &r.results[index]
}

// URLTestBatch performs latency tests on multiple outbounds concurrently
// outboundTags: newline-separated outbound tags
// Returns BatchURLTestResult with all results
func (s *BoxService) URLTestBatch(outboundTags string, url string, timeoutMs int32, concurrency int) *BatchURLTestResult {
	if s == nil || s.instance == nil {
		log.Println("[KunBox URLTest] Service not available for batch test")
		return nil
	}

	tags := ParseTags(outboundTags)
	if len(tags) == 0 {
		return nil
	}

	if concurrency <= 0 {
		concurrency = 10
	}

	results := make([]URLTestResult, len(tags))
	sem := make(chan struct{}, concurrency)

	for i, tag := range tags {
		sem <- struct{}{}
		go func(idx int, outboundTag string) {
			defer func() { <-sem }()

			delay := s.URLTestOutbound(outboundTag, url, timeoutMs)
			results[idx] = URLTestResult{
				Tag:   outboundTag,
				Delay: delay,
			}
		}(i, tag)
	}

	// Wait for all goroutines
	for i := 0; i < concurrency; i++ {
		sem <- struct{}{}
	}

	return &BatchURLTestResult{results: results}
}

// GetURLTestHistory retrieves stored test history for an outbound
func (s *BoxService) GetURLTestHistory(outboundTag string) int32 {
	if s == nil || s.urlTestHistoryStorage == nil {
		return -1
	}

	history := s.urlTestHistoryStorage.LoadURLTestHistory(outboundTag)
	if history == nil {
		return -1
	}

	return int32(history.Delay)
}
