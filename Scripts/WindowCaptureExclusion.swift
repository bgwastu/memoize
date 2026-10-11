import AppKit
import Darwin

/// Diagnostic only: this method blanked the local note during a live Meet share.
/// Experimental window-server capture exclusion, as used by Chromium's
/// NativeWidgetNSWindowBridge. Resolve private APIs at runtime so their absence
/// disables the feature instead of preventing Memoize from launching.
@MainActor final class WindowCaptureExclusion {
    private typealias MainConnection = @convention(c) () -> UInt32
    private typealias CreateRegion = @convention(c) (CGRect) -> Unmanaged<CFTypeRef>?
    private typealias SetExcludeShape = @convention(c) (UInt32, Int, CFTypeRef?) -> Int32

    private let mainConnection: MainConnection? = WindowCaptureExclusion.resolve("CGSMainConnectionID", "SLSMainConnectionID")
    private let createRegion: CreateRegion? = WindowCaptureExclusion.resolve("CGRegionCreateWithRect")
    private let setExcludeShape: SetExcludeShape? = WindowCaptureExclusion.resolve("CGSSetWindowCaptureExcludeShape", "SLSSetWindowCaptureExcludeShape")

    var isAvailable: Bool { mainConnection != nil && createRegion != nil && setExcludeShape != nil }

    func apply(excluding: Bool, to window: NSWindow) throws {
        guard let mainConnection, let createRegion, let setExcludeShape else { throw Failure.unavailable }
        guard window.windowNumber > 0 else { throw Failure.windowUnavailable }
        let region: CFTypeRef?
        if excluding {
            // The region uses window coordinates, including the title bar.
            let bounds = CGRect(origin:.zero,size:window.frame.size)
            guard bounds.width > 0, bounds.height > 0,
                  let created = createRegion(bounds)?.takeRetainedValue() else { throw Failure.regionUnavailable }
            region = created
        } else {
            // A null region restores the normal capture behavior.
            region = nil
        }
        let result = setExcludeShape(mainConnection(),window.windowNumber,region)
        withExtendedLifetime(region) { }
        guard result == 0 else { throw Failure.rejected(result) }
    }

    private static func resolve<Function>(_ names: String...) -> Function? {
        let defaultHandle = UnsafeMutableRawPointer(bitPattern:-2) // Darwin RTLD_DEFAULT
        for name in names {
            if let address = dlsym(defaultHandle,name) { return unsafeBitCast(address,to:Function.self) }
        }
        return nil
    }

    private enum Failure: LocalizedError {
        case unavailable, windowUnavailable, regionUnavailable, rejected(Int32)
        var errorDescription: String? {
            switch self {
            case .unavailable: "Screen-share hiding is unavailable on this version of macOS."
            case .windowUnavailable: "The note window is not ready for screen-share hiding."
            case .regionUnavailable: "macOS could not prepare screen-share hiding for this note."
            case .rejected(let code): "macOS could not change screen-share visibility (error \(code))."
            }
        }
    }
}
