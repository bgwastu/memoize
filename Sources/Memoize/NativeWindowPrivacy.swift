import AppKit

/// Experimental: the legacy AppKit flag passed full-display capture tests on
/// macOS 27.0.1, but Apple explicitly does not guarantee capture exclusion.
/// Keep this opt-in until physical local readability has also been verified.
@MainActor enum NativeWindowPrivacy {
    static let preferenceKey = "experimentalNativeWindowPrivacy"
    static var testOverride: Bool?
    // The system-owned menu-bar icon contains no note and ignores this flag.
    static weak var statusBarWindow: NSWindow?
    static var isEnabled: Bool { testOverride ?? UserDefaults.standard.bool(forKey:preferenceKey) }

    static func apply(to window: NSWindow?) {
        guard let window, window !== statusBarWindow else { return }
        let sharing: NSWindow.SharingType = isEnabled ? .none : .readOnly
        if window.sharingType != sharing { window.sharingType = sharing }
    }

    static func applyToAllWindows() {
        for window in NSApp.windows { apply(to:window) }
    }
}
