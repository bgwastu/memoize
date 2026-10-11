import AppKit

final class CaptureProbePanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
    private var captureSize: NSSize?
    private var wasResizable = false

    /// Current macOS capture exclusion can leave a visible strip when a window
    /// grows. Freeze both interactive and programmatic sizing during exclusion.
    func setCaptureSizeLocked(_ locked: Bool) {
        if locked {
            guard captureSize == nil else { return }
            captureSize = frame.size; wasResizable = styleMask.contains(.resizable)
            styleMask.remove(.resizable)
        } else {
            captureSize = nil
            if wasResizable { styleMask.insert(.resizable) }
            wasResizable = false
        }
    }
    override func setFrame(_ frameRect: NSRect, display flag: Bool) {
        var rect = frameRect
        if let captureSize { rect.size = captureSize }
        super.setFrame(rect,display:flag)
    }
    override func setFrame(_ frameRect: NSRect, display flag: Bool, animate: Bool) {
        var rect = frameRect
        if let captureSize { rect.size = captureSize }
        super.setFrame(rect,display:flag,animate:animate)
    }
    override func setContentSize(_ size: NSSize) {
        guard captureSize == nil else { return }
        super.setContentSize(size)
    }
}
