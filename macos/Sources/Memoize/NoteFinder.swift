import AppKit

/// Keep AppKit's find bar and keyboard behavior, with exact TextKit 1 geometry.
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
        guard let view = textView, let layout = view.layoutManager, let container = view.textContainer else { return [] }
        let origin = view.textContainerOrigin
        let glyphs = layout.glyphRange(forBoundingRect:view.visibleRect.offsetBy(dx:-origin.x,dy:-origin.y),in:container)
        return [NSValue(range:layout.characterRange(forGlyphRange:glyphs,actualGlyphRange:nil))]
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
        guard let view = textView, let layout = view.layoutManager, let container = view.textContainer,
              range.location != NSNotFound, NSMaxRange(range) <= (view.textStorage?.length ?? 0) else { return nil }
        let origin = view.textContainerOrigin
        let glyphs = layout.glyphRange(forCharacterRange:range,actualCharacterRange:nil)
        var rects: [NSValue] = []
        layout.enumerateEnclosingRects(forGlyphRange:glyphs,withinSelectedGlyphRange:NSRange(location:NSNotFound,length:0),in:container) { rect,_ in
            rects.append(NSValue(rect:rect.offsetBy(dx:origin.x,dy:origin.y)))
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
