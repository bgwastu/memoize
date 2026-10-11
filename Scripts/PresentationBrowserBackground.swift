import AppKit

/// Opaque synthetic desktop for browser capture tests. This separate process
/// stays in the owned desktop stream while Memoize itself is excluded.
@main struct PresentationBrowserBackground {
    @MainActor static func main() {
        let application = NSApplication.shared
        application.setActivationPolicy(.accessory)
        guard let screen = NSScreen.screens.first else { exit(1) }
        let window = NSPanel(contentRect:screen.frame,styleMask:[.borderless,.nonactivatingPanel],backing:.buffered,defer:false)
        window.backgroundColor = .green; window.isOpaque = true; window.hasShadow = false
        window.collectionBehavior = [.canJoinAllSpaces,.fullScreenAuxiliary,.canJoinAllApplications]
        window.level = .normal; window.hidesOnDeactivate = false
        window.isReleasedWhenClosed = false; window.orderFrontRegardless()
        if let windows = CGWindowListCopyWindowInfo(.optionOnScreenOnly,kCGNullWindowID) as? [[String:Any]],
           let presentation = windows.first(where:{ $0[kCGWindowName as String] as? String == "Memoize Presentation" }),
           let number = presentation[kCGWindowNumber as String] as? Int {
            window.order(.below,relativeTo:number)
        }
        let stopFile = ProcessInfo.processInfo.arguments.dropFirst().first
        let deadline = Date().addingTimeInterval(120)
        let timer = Timer(timeInterval:0.1,repeats:true) { _ in
            if Date() >= deadline || stopFile.map({ FileManager.default.fileExists(atPath:$0) }) == true { exit(0) }
        }
        RunLoop.main.add(timer,forMode:.common)
        application.run()
        withExtendedLifetime(window) { }
    }
}
