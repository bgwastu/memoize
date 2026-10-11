import AppKit
import ScreenCaptureKit
import CoreMedia
import CoreVideo

/// Default capture includes only synthetic red and green windows.
/// --whole-display uses display capture cropped to the opaque fixture window.
/// Only sample counts are reported; captured frames are never saved.
/// Existing screen-recording permission is required; this probe never requests it.
@main struct ScreenShareProbe {
    @MainActor static func main() {
        let application = NSApplication.shared
        application.setActivationPolicy(.accessory)
        Task { @MainActor in
            do { try await run(); exit(0) }
            catch { FileHandle.standardError.write(Data("Capture probe failed: \(error.localizedDescription)\n".utf8)); exit(1) }
        }
        application.run()
    }

    @MainActor private static func run() async throws {
        let exclusion = WindowCaptureExclusion()
        guard exclusion.isAvailable, let screen = NSScreen.main else { throw ProbeError("Capture exclusion or a display is unavailable.") }
        let frame = NSRect(x:screen.visibleFrame.midX - 230,y:screen.visibleFrame.midY - 180,width:460,height:360)
        let background = window(frame:frame,color:.green)
        let note = window(frame:frame.insetBy(dx:50,dy:50),color:.red)
        defer { try? exclusion.apply(excluding:false,to:note); note.orderOut(nil); background.orderOut(nil) }
        background.orderFrontRegardless(); note.orderFrontRegardless()
        try await Task.sleep(for:.milliseconds(200))
        let lockedSize = note.frame.size
        note.setCaptureSizeLocked(true)
        try exclusion.apply(excluding:true,to:note)
        note.setContentSize(NSSize(width:390,height:290))
        guard note.frame.size == lockedSize, !note.styleMask.contains(.resizable) else { throw ProbeError("Capture size lock did not prevent growth.") }
        try exclusion.apply(excluding:false,to:note)
        note.setCaptureSizeLocked(false)
        note.setContentSize(NSSize(width:390,height:290))
        try await Task.sleep(for:.milliseconds(200))
        print("Native API accepted exclusion/restoration; capture size lock prevents growth and restores normal sizing.")

        guard CGPreflightScreenCaptureAccess() else {
            print("SKIPPED captured-pixel verification: screen-recording permission is unavailable. No permission was requested. Meet behavior remains unverified.")
            return
        }
        let content = try await SCShareableContent.excludingDesktopWindows(false,onScreenWindowsOnly:true)
        let ids = Set([CGWindowID(background.windowNumber),CGWindowID(note.windowNumber)])
        let fixtures = content.windows.filter { ids.contains($0.windowID) }
        guard fixtures.count == 2, let capturedNote = fixtures.first(where:{ $0.windowID == CGWindowID(note.windowNumber) }),
              let display = content.displays.first(where:{ $0.frame.contains(capturedNote.frame.origin) }) else {
            throw ProbeError("The synthetic windows were not available to ScreenCaptureKit.")
        }
        let wholeDisplay = ProcessInfo.processInfo.arguments.contains("--whole-display")
        func makeFilter(_ windows: [SCWindow]) -> SCContentFilter {
            wholeDisplay ? SCContentFilter(display:display,excludingWindows:[]) : SCContentFilter(display:display,including:windows)
        }
        var filter = makeFilter(fixtures)
        let configuration = SCStreamConfiguration()
        let captureRect: CGRect
        if wholeDisplay {
            guard let capturedBackground = fixtures.first(where:{ $0.windowID == CGWindowID(background.windowNumber) }) else {
                throw ProbeError("The opaque fixture background was unavailable.")
            }
            captureRect = capturedBackground.frame.offsetBy(dx:-display.frame.minX,dy:-display.frame.minY)
            configuration.sourceRect = captureRect
        } else { captureRect = filter.contentRect }
        configuration.width = Int(captureRect.width * CGFloat(filter.pointPixelScale))
        configuration.height = Int(captureRect.height * CGFloat(filter.pointPixelScale))
        print("Capture path: \(wholeDisplay ? "whole display, cropped to fixtures" : "fixture windows only")")
        configuration.showsCursor = false
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.minimumFrameInterval = CMTime(value:1,timescale:10)

        // Count red pixels across the whole filtered output. This handles capture
        // scaling/cropping and also catches a newly exposed strip after resizing.
        func snapshot(_ phase: String) async throws -> Int {
            try await Task.sleep(for:.milliseconds(150))
            let image = try await SCScreenshotManager.captureImage(contentFilter:filter,configuration:configuration)
            let bitmap = NSBitmapImageRep(cgImage:image)
            var red = 0
            for y in stride(from:0,to:bitmap.pixelsHigh,by:8) {
                for x in stride(from:0,to:bitmap.pixelsWide,by:8) {
                    if let color = bitmap.colorAt(x:x,y:y)?.usingColorSpace(.deviceRGB),
                       color.redComponent > color.greenComponent + 0.3,
                       color.redComponent > color.blueComponent + 0.3 { red += 1 }
                }
            }
            print("Snapshot \(phase): \(red) red fixture samples")
            return red
        }
        let visible = try await snapshot("visible")
        guard visible > 100 else { throw ProbeError("The snapshot baseline did not show the fixture.") }
        note.setCaptureSizeLocked(true)
        try exclusion.apply(excluding:true,to:note)
        guard try await snapshot("excluded") == 0, note.isVisible else { throw ProbeError("The snapshot captured the excluded fixture or hid it locally.") }
        note.setContentSize(NSSize(width:410,height:310))
        note.setFrame(NSRect(origin:note.frame.origin,size:NSSize(width:410,height:310)),display:true)
        guard note.frame.size == NSSize(width:390,height:290) else { throw ProbeError("The excluded fixture grew despite its size lock.") }
        guard try await snapshot("excluded after attempted resize") == 0 else { throw ProbeError("Attempting to resize exposed the fixture.") }
        try exclusion.apply(excluding:false,to:note)
        note.setCaptureSizeLocked(false)
        guard try await snapshot("restored") > 100 else { throw ProbeError("Clearing exclusion did not restore capture.") }
        note.setContentSize(NSSize(width:410,height:310))
        try await Task.sleep(for:.milliseconds(200))
        let resized = try await SCShareableContent.excludingDesktopWindows(false,onScreenWindowsOnly:true)
        filter = makeFilter(resized.windows.filter { ids.contains($0.windowID) })
        let restored = try await snapshot("restored after resize")
        guard restored > visible else { throw ProbeError("Restoration did not show the fixture's expanded area.") }
        note.setCaptureSizeLocked(true)
        try exclusion.apply(excluding:true,to:note)
        guard try await snapshot("excluded at new size") == 0 else { throw ProbeError("Exclusion did not cover the resized fixture.") }
        try exclusion.apply(excluding:false,to:note)
        note.setCaptureSizeLocked(false)
        print("ScreenCaptureKit fixture snapshots passed, including size locking and exclusion after resizing while visible.")

        let output = StreamSamples()
        let stream = SCStream(filter:filter,configuration:configuration,delegate:nil)
        try stream.addStreamOutput(output,type:.screen,sampleHandlerQueue:DispatchQueue(label:"memoize.capture-probe"))
        func liveSample(_ phase: String, after sequence: Int = 0) async throws -> StreamSamples.Sample {
            let deadline = ContinuousClock.now.advanced(by:.seconds(3))
            while (output.latest?.sequence ?? 0) <= sequence, ContinuousClock.now < deadline {
                try await Task.sleep(for:.milliseconds(20))
            }
            try await Task.sleep(for:.milliseconds(200))
            guard let sample = output.latest, sample.sequence > sequence else { throw ProbeError("No new stream frame for \(phase).") }
            print("Stream \(phase): \(sample.red) red fixture samples")
            return sample
        }
        do {
            try await stream.startCapture()
            let before = try await liveSample("visible")
            guard before.red > 100 else { throw ProbeError("The stream baseline did not show the fixture.") }
            note.setCaptureSizeLocked(true)
            try exclusion.apply(excluding:true,to:note)
            let excluded = try await liveSample("excluded",after:before.sequence)
            guard excluded.red == 0, note.isVisible else { throw ProbeError("The stream captured the excluded fixture or hid it locally.") }
            try exclusion.apply(excluding:false,to:note)
            note.setCaptureSizeLocked(false)
            let after = try await liveSample("restored",after:excluded.sequence)
            guard after.red > 100 else { throw ProbeError("The stream did not restore the fixture.") }
            try await stream.stopCapture()
        } catch {
            try? await stream.stopCapture()
            throw error
        }
        print("ScreenCaptureKit fixture stream passed, including toggles on a static desktop. Google Meet still needs a receiver-side test.")
    }

    @MainActor private static func window(frame: NSRect, color: NSColor) -> CaptureProbePanel {
        let window = CaptureProbePanel(contentRect:frame,styleMask:[.resizable],backing:.buffered,defer:false)
        window.backgroundColor = color; window.isOpaque = true; window.hasShadow = false
        window.hidesOnDeactivate = false; window.isFloatingPanel = true
        window.isReleasedWhenClosed = false; window.level = .floating
        return window
    }
    private struct ProbeError: LocalizedError {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }

    private final class StreamSamples: NSObject, SCStreamOutput, @unchecked Sendable {
        struct Sample: Sendable { let sequence: Int; let red: Int }
        private let lock = NSLock()
        private var value: Sample?
        var latest: Sample? { lock.withLock { value } }

        func stream(_ stream: SCStream, didOutputSampleBuffer buffer: CMSampleBuffer, of type: SCStreamOutputType) {
            guard type == .screen,
                  let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer,createIfNecessary:false) as? [[SCStreamFrameInfo:Any]],
                  let status = attachments.first?[.status] as? Int, status == SCFrameStatus.complete.rawValue,
                  let pixels = CMSampleBufferGetImageBuffer(buffer),
                  CVPixelBufferGetPixelFormatType(pixels) == kCVPixelFormatType_32BGRA else { return }
            CVPixelBufferLockBaseAddress(pixels,.readOnly)
            defer { CVPixelBufferUnlockBaseAddress(pixels,.readOnly) }
            guard let base = CVPixelBufferGetBaseAddress(pixels)?.assumingMemoryBound(to:UInt8.self) else { return }
            var red = 0
            for y in stride(from:0,to:CVPixelBufferGetHeight(pixels),by:8) {
                let row = base.advanced(by:y * CVPixelBufferGetBytesPerRow(pixels))
                for x in stride(from:0,to:CVPixelBufferGetWidth(pixels),by:8) {
                    let pixel = row.advanced(by:x * 4)
                    if Int(pixel[2]) > Int(pixel[1]) + 76, Int(pixel[2]) > Int(pixel[0]) + 76 { red += 1 }
                }
            }
            lock.withLock { value = Sample(sequence:(value?.sequence ?? 0) + 1,red:red) }
        }
    }
}
