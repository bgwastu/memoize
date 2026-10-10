import AppKit

/// Keep AppKit's find bar and keyboard behavior, with precise TextKit 2 geometry.
@MainActor final class NoteFinder: NSObject, @MainActor NSTextFinderClient {
    let finder = NSTextFinder()
    private weak var textView: SourceTextView?
    private var observation: NSKeyValueObservation?

    init(textView: SourceTextView) {
        self.textView = textView
        super.init()
        finder.client = self
        finder.findBarContainer = textView.enclosingScrollView
        finder.isIncrementalSearchingEnabled = true
        finder.incrementalSearchingShouldDimContentView = false
        observation = finder.observe(\.incrementalMatchRanges) { [weak textView] _,_ in
            MainActor.assumeIsolated { textView?.needsDisplay = true }
        }
    }
    var string: String { textView?.string ?? "" }
    var isEditable: Bool { false }
    var isSelectable: Bool { true }
    var allowsMultipleSelection: Bool { true }
    var firstSelectedRange: NSRange { textView?.selectedRange() ?? NSRange(location:0,length:0) }
    var selectedRanges: [NSValue] {
        get { textView?.selectedRanges ?? [] }
        set {
            textView?.selectedRanges = newValue
        }
    }
    var visibleCharacterRanges: [NSValue] {
        guard let manager = textView?.textLayoutManager, let content = manager.textContentManager else { return [] }
        let range = manager.textViewportLayoutController.viewportRange ?? content.documentRange
        return [NSValue(range:NSRange(location:content.offset(from:content.documentRange.location,to:range.location),length:content.offset(from:range.location,to:range.endLocation)))]
    }
    func scrollRangeToVisible(_ range: NSRange) {
        guard range.location != NSNotFound, NSMaxRange(range) <= (string as NSString).length else { return }
        // AppKit can reveal a match without assigning selectedRanges to this find-only client.
        // Keep the active selection in step so subsequent Next/Previous actions advance.
        textView?.setSelectedRange(range)
        textView?.scrollRangeToVisible(range)
    }
    func contentView(at index: Int, effectiveCharacterRange outRange: NSRangePointer) -> NSView {
        outRange.pointee = NSRange(location:0,length:(string as NSString).length)
        return textView ?? NSView()
    }
    func rects(forCharacterRange range: NSRange) -> [NSValue]? {
        guard let view = textView, let manager = view.textLayoutManager, let content = manager.textContentManager,
              range.location != NSNotFound, NSMaxRange(range) <= (view.string as NSString).length,
              let start = content.location(content.documentRange.location,offsetBy:range.location),
              let end = content.location(start,offsetBy:range.length), let textRange = NSTextRange(location:start,end:end) else { return nil }
        var rects: [NSValue] = []
        manager.enumerateTextSegments(in:textRange,type:.standard,options:[]) { _,rect,_,_ in
            rects.append(NSValue(rect:rect.offsetBy(dx:view.textContainerOrigin.x,dy:view.textContainerOrigin.y)))
            return true
        }
        return rects
    }
    func show(query: String? = nil) {
        var match: NSRange?
        if let query, !query.isEmpty, let view = textView {
            let range = (view.string as NSString).range(of:query,options:[.caseInsensitive,.literal])
            if range.location != NSNotFound {
                match = range
                view.setSelectedRange(range)
                finder.performAction(.setSearchString)
            }
        }
        finder.performAction(.showFindInterface)
        // Showing the native bar can restore its previous selection. A supplied
        // query (for example a library result) must select this match afterward.
        if let match { scrollRangeToVisible(match) }
    }
    func drawMatches(in dirtyRect: NSRect) {
        for value in finder.incrementalMatchRanges {
            for rect in rects(forCharacterRange:value.rangeValue) ?? [] where rect.rectValue.intersects(dirtyRect) {
                NSTextFinder.drawIncrementalMatchHighlight(in:rect.rectValue)
            }
        }
    }
}
