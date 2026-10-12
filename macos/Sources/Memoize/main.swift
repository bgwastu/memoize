import AppKit
import MemoizeCore

let appStart = ContinuousClock.now
let application = NSApplication.shared
let controller = AppController()
application.delegate = controller
application.run()
