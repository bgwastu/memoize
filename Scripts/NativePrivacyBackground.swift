import AppKit

/// A solid test desktop behind the synthetic note, including full-screen Spaces.
@main struct NativePrivacyBackground {
    @MainActor static func main() {
        let application = NSApplication.shared
        application.setActivationPolicy(.accessory)
        guard let screen = NSScreen.screens.first else { exit(1) }
        let window = NSPanel(contentRect:screen.frame,styleMask:[.borderless,.nonactivatingPanel],backing:.buffered,defer:false)
        window.backgroundColor = .green
        window.isOpaque = true
        window.hasShadow = false
        window.hidesOnDeactivate = false
        window.collectionBehavior = [.canJoinAllSpaces,.fullScreenAuxiliary,.canJoinAllApplications]
        window.level = NSWindow.Level(rawValue:1)
        window.isReleasedWhenClosed = false
        window.orderFrontRegardless()
        let stopFile = CommandLine.arguments[1]
        let deadline = Date().addingTimeInterval(900)
        let timer = Timer(timeInterval:0.1,repeats:true) { _ in
            if Date() >= deadline || FileManager.default.fileExists(atPath:stopFile) { exit(0) }
        }
        RunLoop.main.add(timer,forMode:.common)
        application.run()
        withExtendedLifetime(window) { }
    }
}
