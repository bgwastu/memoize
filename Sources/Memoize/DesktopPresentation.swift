import AppKit
@preconcurrency import ScreenCaptureKit
import CoreImage
import CoreMedia
import CoreVideo

/// Own the capture so filtering never changes the local note's window.
/// Meet must share this output window rather than the physical display.
@MainActor final class DesktopPresentation: NSObject, NSWindowDelegate, SCStreamDelegate {
    let canvas = PresentationCanvas(frame:NSRect(x:0,y:0,width:880,height:550))
    let window: NSWindow
    private(set) var isStarting = false
    private(set) var isPresenting = false
    private(set) var includesNote = false
    private(set) var error: String?
    var noteSnapshot: (() -> NSImage?)?
    var preferredScreen: (() -> NSScreen?)?
    var onChange: (() -> Void)?
    var onNotice: ((String) -> Void)?
    private var stream: SCStream?
    private var frames: PresentationFrames?
    private var timer: Timer?
    private var session = UUID()
    private var lastFrame = 0
    private var lastNoteUpdate = Date.distantPast

    override init() {
        window = NSWindow(contentRect:canvas.bounds,styleMask:[.titled,.closable,.miniaturizable,.resizable],backing:.buffered,defer:false)
        super.init()
        window.title = "Memoize Presentation"; window.contentView = canvas
        window.isReleasedWhenClosed = false; window.minSize = NSSize(width:480,height:300)
        window.delegate = self
        canvas.autoresizingMask = [.width,.height]
    }

    func toggle() {
        guard !isStarting else { return }
        if isPresenting { setIncludesNote(!includesNote) }
        else { chooseDisplay() }
    }

    private func chooseDisplay() {
        session = UUID(); isStarting = true; includesNote = false; error = nil
        onChange?()
        let screens = NSScreen.screens
        guard !screens.isEmpty else { failed("No screen is available to present."); return }
        var selected = screens.firstIndex(where:{ $0 === preferredScreen?() }) ?? 0
        if screens.count > 1 {
            let alert = NSAlert(); alert.messageText = "Choose a screen to present"
            alert.informativeText = "In Meet, share the Memoize Presentation window. Your note starts private."
            alert.addButton(withTitle:"Start Presentation"); alert.addButton(withTitle:"Cancel")
            let chooser = NSPopUpButton(frame:NSRect(x:0,y:0,width:300,height:26),pullsDown:false)
            chooser.addItems(withTitles:screens.enumerated().map { "\($0.offset + 1). \($0.element.localizedName)" })
            chooser.selectItem(at:selected); alert.accessoryView = chooser
            guard alert.runModal() == .alertFirstButtonReturn else { stop(); return }
            selected = chooser.indexOfSelectedItem
        }
        let displayID = (screens[selected].deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value
        let token = session
        Task { @MainActor [weak self] in
            guard let self, token == session else { return }
            do {
                if !CGPreflightScreenCaptureAccess() { _ = CGRequestScreenCaptureAccess() }
                guard CGPreflightScreenCaptureAccess() else {
                    throw PresentationFailure("Allow Screen Recording for Memoize in System Settings, then try Present again. macOS may require restarting Memoize after granting access.")
                }
                let content = try await SCShareableContent.current
                guard token == session else { return }
                guard let display = content.displays.first(where:{ $0.displayID == displayID }) else {
                    throw PresentationFailure("The selected screen is no longer available.")
                }
                try await start(display:display)
            } catch { if token == session { failed(error.localizedDescription) } }
        }
    }

    /// The selected display comes from the local screen choice in normal operation.
    /// Diagnostics may supply a display only when capture permission already exists.
    func start(display: SCDisplay) async throws {
        let token = session
        isStarting = true; error = nil; onChange?()
        let content = try await SCShareableContent.currentProcess
        guard token == session else { return }
        guard let application = content.applications.first(where:{ $0.processID == ProcessInfo.processInfo.processIdentifier }) else {
            throw PresentationFailure("Could not exclude Memoize from the presentation.")
        }
        let filter = Self.desktopFilter(display:display,excluding:application)
        let configuration = SCStreamConfiguration()
        configuration.width = min(display.width,1920)
        configuration.height = max(1,Int(Double(configuration.width) * Double(display.height) / Double(display.width)))
        configuration.minimumFrameInterval = CMTime(value:1,timescale:20)
        configuration.queueDepth = 3; configuration.showsCursor = true
        configuration.capturesAudio = false; configuration.captureMicrophone = false
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        let output = PresentationFrames()
        let candidate = SCStream(filter:filter,configuration:configuration,delegate:self)
        try candidate.addStreamOutput(output,type:.screen,sampleHandlerQueue:DispatchQueue(label:"net.wastu.memoize.presentation"))
        stream = candidate; frames = output
        do {
            try await candidate.startCapture()
            let deadline = ContinuousClock.now.advanced(by:.seconds(3))
            while output.latest?.image == nil, token == session, ContinuousClock.now < deadline {
                try await Task.sleep(for:.milliseconds(20))
            }
            guard token == session else { try? await candidate.stopCapture(); return }
            guard output.latest?.image != nil else { throw PresentationFailure("The selected screen did not produce a presentation frame.") }
            isStarting = false; isPresenting = true; includesNote = false
            window.contentAspectRatio = NSSize(width:display.width,height:display.height)
            window.setContentSize(NSSize(width:880,height:880 * Double(display.height) / Double(display.width)))
            window.center()
            lastFrame = 0; lastNoteUpdate = .distantPast
            refresh()
            let timer = Timer(timeInterval:0.05,repeats:true) { [weak self] _ in
                MainActor.assumeIsolated { self?.refresh() }
            }
            self.timer = timer; RunLoop.main.add(timer,forMode:.common)
            window.makeKeyAndOrderFront(nil)
            onChange?(); onNotice?("In Meet, share the “Memoize Presentation” window.")
        } catch {
            try? await candidate.stopCapture()
            if token == session { stream = nil; frames = nil }
            throw error
        }
    }

    static func desktopFilter(display: SCDisplay, excluding application: SCRunningApplication) -> SCContentFilter {
        // Application exclusion also covers windows opened after capture starts,
        // including the output window, library, Settings and attachment previews.
        SCContentFilter(display:display,excludingApplications:[application],exceptingWindows:[])
    }

    func setIncludesNote(_ included: Bool) {
        guard isPresenting else { return }
        includesNote = included
        canvas.setNote(included ? noteSnapshot?() : nil)
        lastNoteUpdate = Date()
        // Clear the overlay immediately even when the captured desktop is idle.
        canvas.displayIfNeeded()
        onChange?()
    }

    private func refresh() {
        if let frame = frames?.latest, frame.sequence != lastFrame {
            lastFrame = frame.sequence; canvas.setDesktop(frame.image)
        }
        if includesNote, Date().timeIntervalSince(lastNoteUpdate) >= 0.2 {
            lastNoteUpdate = Date(); canvas.setNote(noteSnapshot?())
        }
    }

    func stop() {
        session = UUID(); timer?.invalidate(); timer = nil
        let previous = stream; stream = nil; frames = nil
        isStarting = false; isPresenting = false; includesNote = false
        error = nil; canvas.clear(); canvas.displayIfNeeded(); window.orderOut(nil)
        onChange?()
        if let previous { Task { try? await previous.stopCapture() } }
    }

    private func failed(_ message: String) {
        stop(); error = message; onChange?(); onNotice?(message)
        if !ProcessInfo.processInfo.arguments.contains("--smoke-test") {
            let alert = NSAlert(); alert.messageText = "Couldn’t start the presentation"
            alert.informativeText = message; alert.runModal()
        }
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool { stop(); return false }

    nonisolated func stream(_ stream: SCStream, didStopWithError error: any Error) {
        Task { @MainActor [weak self] in
            guard let self, self.stream === stream else { return }; failed(error.localizedDescription)
        }
    }

    private struct PresentationFailure: LocalizedError {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }
}

@MainActor final class PresentationCanvas: NSView {
    private var desktop: NSImage?
    private var note: NSImage?
    var hasNoteOverlay: Bool { note != nil }
    override var isOpaque: Bool { true }

    func setDesktop(_ image: CGImage?) {
        desktop = image.map { NSImage(cgImage:$0,size:NSSize(width:$0.width,height:$0.height)) }
        needsDisplay = true
    }
    func setNote(_ image: NSImage?) { note = image; needsDisplay = true }
    func clear() { desktop = nil; note = nil; needsDisplay = true }

    override func draw(_ dirtyRect: NSRect) {
        NSColor.black.setFill(); bounds.fill()
        guard let desktop, desktop.size.width > 0, desktop.size.height > 0 else { return }
        let scale = min(bounds.width / desktop.size.width,bounds.height / desktop.size.height)
        let size = NSSize(width:desktop.size.width * scale,height:desktop.size.height * scale)
        let rect = NSRect(x:bounds.midX - size.width / 2,y:bounds.midY - size.height / 2,width:size.width,height:size.height)
        desktop.draw(in:rect,from:.zero,operation:.copy,fraction:1)
        guard let note, note.size.width > 0, note.size.height > 0 else { return }
        let margin = min(18,rect.width * 0.025)
        let noteScale = min(rect.width * 0.38 / note.size.width,(rect.height - 2 * margin) / note.size.height)
        let target = NSRect(x:rect.maxX - margin - note.size.width * noteScale,y:rect.maxY - margin - note.size.height * noteScale,width:note.size.width * noteScale,height:note.size.height * noteScale)
        NSGraphicsContext.saveGraphicsState()
        NSBezierPath(roundedRect:target,xRadius:8,yRadius:8).addClip()
        note.draw(in:target,from:.zero,operation:.sourceOver,fraction:1)
        NSGraphicsContext.restoreGraphicsState()
    }
}

private final class PresentationFrames: NSObject, SCStreamOutput, @unchecked Sendable {
    struct Frame: @unchecked Sendable { let sequence: Int; let image: CGImage? }
    private let lock = NSLock()
    private let context = CIContext(options:[.cacheIntermediates:false])
    private var frame: Frame?
    var latest: Frame? { lock.withLock { frame } }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer,createIfNecessary:false) as? [[SCStreamFrameInfo:Any]],
              let rawStatus = attachments.first?[.status] as? Int,
              let status = SCFrameStatus(rawValue:rawStatus) else { return }
        if status == .blank || status == .suspended || status == .stopped {
            lock.withLock { frame = Frame(sequence:(frame?.sequence ?? 0) + 1,image:nil) }; return
        }
        guard status == .complete, let buffer = sampleBuffer.imageBuffer else { return }
        let image = CIImage(cvPixelBuffer:buffer)
        guard let rendered = context.createCGImage(image,from:image.extent) else { return }
        lock.withLock { frame = Frame(sequence:(frame?.sequence ?? 0) + 1,image:rendered) }
    }
}
