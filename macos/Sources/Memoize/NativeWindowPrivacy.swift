import AppKit

/// Experimental: the legacy AppKit flag passed full-display capture tests on
/// macOS 27.0.1, but Apple explicitly does not guarantee capture exclusion.
/// Keep this opt-in until physical local readability has also been verified.
@MainActor enum NativeWindowPrivacy {
    static let preferenceKey = "experimentalNativeWindowPrivacy"
    static var testOverride: Bool?
    /// The menu-bar icon contains no note. Its window is recreated when the icon is shown again, and Control Center will not draw an icon whose window opts out of capture.
    static weak var statusBarWindow: NSWindow?
    static var isEnabled: Bool { testOverride ?? UserDefaults.standard.bool(forKey:preferenceKey) }

    static func isStatusItemWindow(_ window: NSWindow) -> Bool {
        window === statusBarWindow || window.level == .statusBar
    }

    static func apply(to window: NSWindow?) {
        guard let window, !isStatusItemWindow(window) else { return }
        let sharing: NSWindow.SharingType = isEnabled ? .none : .readOnly
        if window.sharingType != sharing { window.sharingType = sharing }
    }

    static func applyToAllWindows() {
        for window in NSApp.windows { apply(to:window) }
    }
}
