package libbox

import (
	"context"
	"log"
	runtimeDebug "runtime/debug"
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/conntrack"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/experimental/deprecated"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service"
)

// KunBox Hot Reload Extension for libbox
// Implements config hot reload without destroying Android VpnService
// Core idea: Keep BoxService shell, only rebuild internal box.Box instance
//
// Version: 1.2.0
// Compatible with: sing-box v1.12.x

var (
	reloadMutex sync.Mutex

	globalPlatformInterface PlatformInterface
	globalBoxService        *BoxService
	globalReloadCount       int
	globalStateLock         sync.Mutex
)

// SetReloadablePlatformInterface sets the platform interface for hot reload.
// Must be called before NewService.
// This interface will be reused during hot reload.
func SetReloadablePlatformInterface(platformInterface PlatformInterface) {
	globalStateLock.Lock()
	defer globalStateLock.Unlock()
	globalPlatformInterface = platformInterface
	log.Println("[KunBox Reload] Platform interface set for hot reload")
}

// SetReloadableBoxService sets the BoxService for hot reload.
// Call this after NewService succeeds.
func SetReloadableBoxService(service *BoxService) {
	globalStateLock.Lock()
	defer globalStateLock.Unlock()
	globalBoxService = service
	log.Println("[KunBox Reload] BoxService set for hot reload")
}

// ClearReloadableService clears the hot reload state.
// Call this when service stops.
func ClearReloadableService() {
	globalStateLock.Lock()
	defer globalStateLock.Unlock()
	globalBoxService = nil
	globalPlatformInterface = nil
	globalReloadCount = 0
	log.Println("[KunBox Reload] Reloadable service cleared")
}

// CanReload checks if hot reload is available.
func CanReload() bool {
	globalStateLock.Lock()
	defer globalStateLock.Unlock()
	return globalBoxService != nil && globalPlatformInterface != nil && globalBoxService.instance != nil
}

// GetReloadCount returns the number of hot reloads performed.
func GetReloadCount() int {
	globalStateLock.Lock()
	defer globalStateLock.Unlock()
	return globalReloadCount
}

// ReloadConfig performs hot reload with new config.
// Parameters:
//   - configContent: new config JSON content
//   - preserveSelector: whether to preserve currently selected node
//
// Returns error if reload fails, nil on success.
// Note: This method blocks until reload completes.
func ReloadConfig(configContent string, preserveSelector bool) error {
	reloadMutex.Lock()
	defer reloadMutex.Unlock()

	globalStateLock.Lock()
	svc := globalBoxService
	platformInterface := globalPlatformInterface
	reloadNum := globalReloadCount + 1
	globalStateLock.Unlock()

	if svc == nil {
		return E.New("no BoxService available for reload")
	}
	if platformInterface == nil {
		return E.New("no PlatformInterface available for reload")
	}

	log.Printf("[KunBox Reload] Starting reload #%d...", reloadNum)

	// 1. Save currently selected node if needed
	var selectedOutbound string
	if preserveSelector && svc.instance != nil {
		selectedOutbound = getSelectedOutboundFromService(svc)
		if selectedOutbound != "" {
			log.Printf("[KunBox Reload] Preserving selected outbound: %s", selectedOutbound)
		}
	}

	// 2. Create base context (reuse NewService logic)
	ctx := BaseContext(platformInterface)
	service.MustRegister[deprecated.Manager](ctx, new(deprecatedManager))

	// 3. Parse new config
	options, err := parseConfig(ctx, configContent)
	if err != nil {
		log.Printf("[KunBox Reload] Failed to parse config: %v", err)
		return E.Cause(err, "parse config")
	}
	runtimeDebug.FreeOSMemory()

	// 4. Close all existing connections
	log.Println("[KunBox Reload] Closing existing connections...")
	conntrack.Close()

	// 5. Close old box instance
	if svc.instance != nil {
		log.Println("[KunBox Reload] Closing old box instance...")
		err = svc.instance.Close()
		if err != nil {
			log.Printf("[KunBox Reload] Warning: failed to close old instance: %v", err)
		}
	}

	// 6. Cancel old context
	if svc.cancel != nil {
		svc.cancel()
	}

	// 7. Create new context
	ctx, cancel := context.WithCancel(ctx)
	svc.ctx = ctx
	svc.cancel = cancel

	// 8. Register urlTestHistoryStorage
	urlTestHistoryStorage := urltest.NewHistoryStorage()
	ctx = service.ContextWithPtr(ctx, urlTestHistoryStorage)
	svc.urlTestHistoryStorage = urlTestHistoryStorage

	// 9. Wrap platform interface and register to context
	platformWrapper := &platformInterfaceWrapper{
		iif:       platformInterface,
		useProcFS: platformInterface.UseProcFS(),
	}
	service.MustRegister[platform.Interface](ctx, platformWrapper)

	// 10. Create new box instance
	log.Println("[KunBox Reload] Creating new box instance...")
	newInstance, err := box.New(box.Options{
		Context:           ctx,
		Options:           options,
		PlatformLogWriter: platformWrapper,
	})
	if err != nil {
		log.Printf("[KunBox Reload] Failed to create new instance: %v", err)
		cancel()
		return E.Cause(err, "create box instance")
	}
	runtimeDebug.FreeOSMemory()

	// 11. Start new instance
	log.Println("[KunBox Reload] Starting new box instance...")
	err = newInstance.Start()
	if err != nil {
		log.Printf("[KunBox Reload] Failed to start new instance: %v", err)
		newInstance.Close()
		cancel()
		return E.Cause(err, "start box instance")
	}

	// 12. Update service references
	svc.instance = newInstance
	svc.clashServer = service.FromContext[adapter.ClashServer](ctx)

	// 13. Restore selected node
	if preserveSelector && selectedOutbound != "" {
		restoreSelectedOutboundToService(svc, selectedOutbound)
	}

	// 14. Update global state
	globalStateLock.Lock()
	globalReloadCount = reloadNum
	globalStateLock.Unlock()

	log.Printf("[KunBox Reload] Reload #%d completed successfully", reloadNum)

	return nil
}

func getSelectedOutboundFromService(svc *BoxService) string {
	if svc == nil || svc.instance == nil {
		return ""
	}

	outboundManager := svc.instance.Outbound()
	if outboundManager == nil {
		return ""
	}

	for _, tag := range []string{"proxy", "select", "selector", "PROXY"} {
		outbound, ok := outboundManager.Outbound(tag)
		if !ok {
			continue
		}

		if selector, ok := outbound.(interface{ Now() string }); ok {
			return selector.Now()
		}
	}

	return ""
}

func restoreSelectedOutboundToService(svc *BoxService, tag string) {
	if svc == nil || svc.instance == nil || tag == "" {
		return
	}

	outboundManager := svc.instance.Outbound()
	if outboundManager == nil {
		return
	}

	for _, selectorTag := range []string{"proxy", "select", "selector", "PROXY"} {
		outbound, ok := outboundManager.Outbound(selectorTag)
		if !ok {
			continue
		}

		if selector, ok := outbound.(interface{ SelectOutbound(string) bool }); ok {
			if selector.SelectOutbound(tag) {
				log.Printf("[KunBox Reload] Restored selected outbound: %s", tag)
				return
			}
		}
	}

	log.Printf("[KunBox Reload] Warning: failed to restore outbound %s", tag)
}
