package main

import (
	"os"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

// platformInterfaceStub mirrors the validation-only platform supplied by
// sing-box's libbox.CheckConfig. It must never be used by the runtime path:
// the standalone runner has no Android VPN/TUN platform callbacks.
type platformInterfaceStub struct{}

var _ adapter.PlatformInterface = (*platformInterfaceStub)(nil)

func (s *platformInterfaceStub) Initialize(networkManager adapter.NetworkManager) error {
	return nil
}

func (s *platformInterfaceStub) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (s *platformInterfaceStub) AutoDetectInterfaceControl(fd int) error {
	return nil
}

func (s *platformInterfaceStub) UsePlatformInterface() bool {
	return false
}

func (s *platformInterfaceStub) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	return nil, os.ErrInvalid
}

func (s *platformInterfaceStub) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (s *platformInterfaceStub) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor {
	return (*interfaceMonitorStub)(nil)
}

func (s *platformInterfaceStub) UsePlatformNetworkInterfaces() bool {
	return false
}

func (s *platformInterfaceStub) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, os.ErrInvalid
}

func (s *platformInterfaceStub) UnderNetworkExtension() bool {
	return false
}

func (s *platformInterfaceStub) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (s *platformInterfaceStub) ClearDNSCache() {
}

func (s *platformInterfaceStub) RequestPermissionForWIFIState() error {
	return nil
}

func (s *platformInterfaceStub) ReadWIFIState() adapter.WIFIState {
	return adapter.WIFIState{}
}

func (s *platformInterfaceStub) SystemCertificates() []string {
	return nil
}

func (s *platformInterfaceStub) UsePlatformConnectionOwnerFinder() bool {
	return false
}

func (s *platformInterfaceStub) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, os.ErrInvalid
}

func (s *platformInterfaceStub) UsePlatformWIFIMonitor() bool {
	return false
}

func (s *platformInterfaceStub) UsePlatformNotification() bool {
	return false
}

func (s *platformInterfaceStub) SendNotification(notification *adapter.Notification) error {
	return nil
}

type interfaceMonitorStub struct{}

func (s *interfaceMonitorStub) Start() error {
	return os.ErrInvalid
}

func (s *interfaceMonitorStub) Close() error {
	return os.ErrInvalid
}

func (s *interfaceMonitorStub) DefaultInterface() *control.Interface {
	return nil
}

func (s *interfaceMonitorStub) OverrideAndroidVPN() bool {
	return false
}

func (s *interfaceMonitorStub) AndroidVPNEnabled() bool {
	return false
}

func (s *interfaceMonitorStub) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	return nil
}

func (s *interfaceMonitorStub) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
}

func (s *interfaceMonitorStub) RegisterMyInterface(interfaceName string) {
}

func (s *interfaceMonitorStub) MyInterface() string {
	return ""
}
