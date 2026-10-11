import AppKit
import ApplicationServices

// This helper may operate only on the synthetic native privacy fixture.
let args = CommandLine.arguments
guard args.count == 3, let pid = Int32(args[1]), ["on","off"].contains(args[2]) else { exit(2) }
_ = NSApplication.shared
_ = NSScreen.screens
let application = AXUIElementCreateApplication(pid)
func attribute(_ element: AXUIElement, _ key: String) -> Any? {
    var value: CFTypeRef?
    return AXUIElementCopyAttributeValue(element,key as CFString,&value) == .success ? value : nil
}
func descendants(_ element: AXUIElement) -> [AXUIElement] {
    [element] + (attribute(element,"AXChildren") as? [AXUIElement] ?? []).flatMap(descendants)
}
func verticalPosition(_ element: AXUIElement) -> CGFloat? {
    guard let value = attribute(element,"AXPosition"), CFGetTypeID(value as CFTypeRef) == AXValueGetTypeID() else { return nil }
    var point = CGPoint.zero
    guard AXValueGetValue(value as! AXValue,.cgPoint,&point) else { return nil }
    return point.y
}
let windows = attribute(application,"AXWindows") as? [AXUIElement] ?? []
guard windows.contains(where:{ attribute($0,"AXTitle") as? String == "Memoize full-screen privacy test" }),
      let general = windows.first(where:{ attribute($0,"AXTitle") as? String == "General" }) else {
    fputs("Expected synthetic fixture and General Settings.\n",stderr); exit(1)
}
let elements = descendants(general)
guard let label = elements.first(where:{ attribute($0,"AXRole") as? String == "AXStaticText" && attribute($0,"AXValue") as? String == "Hide notes in screen shares" }),
      let labelY = verticalPosition(label),
      let toggle = elements.first(where:{ attribute($0,"AXRole") as? String == "AXCheckBox" && abs((verticalPosition($0) ?? -.infinity) - labelY) < 10 }) else {
    fputs("Privacy Settings switch was not accessible.\n",stderr); exit(1)
}
let expected = args[2] == "on" ? 1 : 0
if (attribute(toggle,"AXValue") as? NSNumber)?.intValue != expected {
    guard AXUIElementPerformAction(toggle,kAXPressAction as CFString) == .success else { exit(1) }
}
RunLoop.current.run(until:Date().addingTimeInterval(0.6))
guard (attribute(toggle,"AXValue") as? NSNumber)?.intValue == expected else { exit(1) }
print("General Settings privacy switch: \(args[2])")
