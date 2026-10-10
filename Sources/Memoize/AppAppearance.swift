import AppKit

enum AppAppearance: String, CaseIterable {
    case auto, light, dark

    static var saved: Self {
        Self(rawValue:UserDefaults.standard.string(forKey:"appearance") ?? "") ?? .auto
    }

    var title: String {
        switch self {
        case .auto: "Auto"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    @MainActor func apply() {
        switch self {
        case .auto: NSApp.appearance = nil
        case .light: NSApp.appearance = NSAppearance(named:.aqua)
        case .dark: NSApp.appearance = NSAppearance(named:.darkAqua)
        }
    }
}
