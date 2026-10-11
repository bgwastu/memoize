import AppKit
import Darwin

/// Passive activity query for the status label. Window protection stays armed
/// whenever its setting is on, including before a capture is detected.
@MainActor enum ScreenCaptureProbe {
    private typealias Query = @convention(c) () -> Bool
    private static let query: Query? = {
        for name in ["SLSIsScreenWatcherPresent","CGSIsScreenWatcherPresent"] {
            if let address = dlsym(UnsafeMutableRawPointer(bitPattern:-2),name) {
                return unsafeBitCast(address,to:Query.self)
            }
        }
        return nil
    }()

    static func current() -> Bool? { query?() }
}

@MainActor final class ScreenCaptureMonitor {
    private(set) var isCaptured = false
    var onChange: ((Bool) -> Void)?
    private let probe: @MainActor () -> Bool?
    private var polling: Task<Void,Never>?

    init(probe: @escaping @MainActor () -> Bool? = ScreenCaptureProbe.current) {
        self.probe = probe
    }

    func start() {
        guard polling == nil else { return }
        refresh()
        polling = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for:.milliseconds(500)) } catch { return }
                self?.refresh()
            }
        }
    }

    func refresh() {
        let captured = probe() == true
        guard captured != isCaptured else { return }
        isCaptured = captured
        onChange?(captured)
    }

    func stop() {
        polling?.cancel(); polling = nil
        guard isCaptured else { return }
        isCaptured = false; onChange?(false)
    }

    deinit { polling?.cancel() }
}
