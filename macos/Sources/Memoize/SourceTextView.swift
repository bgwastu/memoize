import AppKit
import QuartzCore
import MemoizeCore

/// Plain-text Markdown source view on TextKit 1. TextKit 1 lays out every line
/// exactly, so long notes keep a stable caret and scroll position while typing.
final class SourceTextView: NSTextView {
    /// TextKit 1 views built from a container do not own their storage.
    private var ownedStorage: NSTextStorage?

    static func make() -> SourceTextView {
        let storage = NSTextStorage()
        let layout = NSLayoutManager()
        // Exact layout everywhere: estimated geometry is what made the caret and viewport jump.
        layout.allowsNonContiguousLayout = false
        storage.addLayoutManager(layout)
        let container = NSTextContainer(size:NSSize(width:0,height:CGFloat.greatestFiniteMagnitude))
        container.widthTracksTextView = true
        layout.addTextContainer(container)
        let view = SourceTextView(frame:.zero,textContainer:container)
        view.ownedStorage = storage
        return view
    }

    /// The source as a native UTF-8 string. AppKit's storage string bridges lazily,
    /// so comparing it byte by byte is slow on long notes; this copies it once.
    var source: String { var text = string; text.makeContiguousUTF8(); return text }

    lazy var noteFinder = NoteFinder(textView:self)
    override func performTextFinderAction(_ sender: Any?) {
        let tag = (sender as? NSMenuItem)?.tag ?? (sender as? NSControl)?.tag ?? NSTextFinder.Action.showFindInterface.rawValue
        if let action = NSTextFinder.Action(rawValue:tag) { noteFinder.finder.performAction(action) }
    }
    override func validateUserInterfaceItem(_ item: any NSValidatedUserInterfaceItem) -> Bool {
        if item.action == #selector(performTextFinderAction(_:)), let action = NSTextFinder.Action(rawValue:item.tag) {
            return noteFinder.finder.validateAction(action)
        }
        if let action = item.action, Self.lineActions.contains(action) {
            return (isEditable || action == #selector(selectWholeLine(_:))) && !hasMarkedText() && selectedRanges.count == 1
        }
        return super.validateUserInterfaceItem(item)
    }
    override func shouldChangeText(in affectedCharRange: NSRange, replacementString: String?) -> Bool {
        enclosingScrollView?.layer?.removeAnimation(forKey:kCATransition)
        if replacementString != nil { noteFinder.finder.noteClientStringWillChange() }
        return super.shouldChangeText(in:affectedCharRange,replacementString:replacementString)
    }
    override func shouldChangeText(inRanges affectedRanges: [NSValue], replacementStrings: [String]?) -> Bool {
        enclosingScrollView?.layer?.removeAnimation(forKey:kCATransition)
        if replacementStrings != nil { noteFinder.finder.noteClientStringWillChange() }
        return super.shouldChangeText(inRanges:affectedRanges,replacementStrings:replacementStrings)
    }
    override func drawBackground(in rect: NSRect) {
        super.drawBackground(in:rect)
        noteFinder.drawMatches(in:rect)
    }

    // MARK: Undo

    var ownUndoManager = UndoManager()
    override var undoManager: UndoManager? { ownUndoManager }
    func withUndoGroup(_ name: String, _ edit: () -> Void) {
        breakUndoCoalescing()
        let manager = ownUndoManager
        // A semantic action must not share the automatic event group with
        // earlier typing, including when it resumes from an async operation.
        if manager.groupsByEvent, manager.groupingLevel == 1, !manager.isUndoing, !manager.isRedoing { manager.endUndoGrouping() }
        let isolated = manager.groupsByEvent && manager.groupingLevel == 0
        if isolated { manager.groupsByEvent = false }
        manager.beginUndoGrouping(); edit()
        manager.setActionName(name); manager.endUndoGrouping()
        if isolated { manager.groupsByEvent = true }
        breakUndoCoalescing()
    }

    // MARK: Markdown editing commands

    var onImage: ((Data,String,String) -> Void)?
    var onFile: ((URL) -> Void)?
    var globalSearch: (() -> Void)?
    /// Return true when the empty note was handled. Return false to delete a remaining character.
    var onEmptyBackspace: (() -> Bool)?
    private var isBlank: Bool {
        !(textStorage?.string ?? "").unicodeScalars.contains { !CharacterSet.whitespacesAndNewlines.contains($0) }
    }
    override func deleteBackward(_ sender: Any?) {
        if !hasMarkedText(), isBlank, let onEmptyBackspace, onEmptyBackspace() { return }
        super.deleteBackward(sender)
    }
    override func insertNewline(_ sender: Any?) {
        guard !hasMarkedText(), selectedRanges.count == 1,
              let edit = MarkdownListEditing.newline(in:string,selection:selectedRange()) else { super.insertNewline(sender); return }
        applyListEdit(edit)
    }
    override func insertTab(_ sender: Any?) {
        guard !hasMarkedText(), selectedRanges.count == 1,
              let edit = MarkdownListEditing.selectionIndent(in:string,selection:selectedRange(),outdent:false) else { super.insertTab(sender); return }
        applyIndentEdit(edit,outdent:false)
    }
    override func insertBacktab(_ sender: Any?) {
        guard !hasMarkedText(), selectedRanges.count == 1,
              let edit = MarkdownListEditing.selectionIndent(in:string,selection:selectedRange(),outdent:true) else {
            if !hasMarkedText(), selectedRanges.count == 1, selectedRange().length > 0 { return }
            super.insertBacktab(sender); return
        }
        applyIndentEdit(edit,outdent:true)
    }
    private func applyIndentEdit(_ edit: MarkdownListEditing.Edit, outdent: Bool) {
        guard selectedRange().length > 0 else { applyListEdit(edit); return }
        guard (string as NSString).substring(with:edit.range) != edit.replacement else { return }
        withUndoGroup(outdent ? "Outdent" : "Indent") {
            replaceSelectedLines(edit.range,with:edit.replacement,selection:edit.selection,undoSelection:selectedRange())
        }
    }
    private func replaceSelectedLines(_ range: NSRange, with replacement: String, selection: NSRange, undoSelection: NSRange) {
        let previous = (string as NSString).substring(with:range)
        ownUndoManager.registerUndo(withTarget:self) { view in
            view.replaceSelectedLines(NSRange(location:range.location,length:replacement.utf16.count),with:previous,selection:undoSelection,undoSelection:selection)
        }
        // Keep native input and change notifications, with one inverse edit that
        // restores the exact selection rather than selecting the replaced block.
        ownUndoManager.disableUndoRegistration()
        insertText(replacement,replacementRange:range)
        ownUndoManager.enableUndoRegistration()
        setSelectedRange(selection)
        breakUndoCoalescing()
    }
    private func applyListEdit(_ edit: MarkdownListEditing.Edit) {
        guard (string as NSString).substring(with:edit.range) != edit.replacement else { return }
        breakUndoCoalescing()
        insertText(edit.replacement,replacementRange:edit.range)
        setSelectedRange(edit.selection)
        scrollRangeToVisible(edit.selection)
        breakUndoCoalescing()
    }
    override func mouseDown(with event: NSEvent) {
        if event.clickCount == 1, event.modifierFlags.intersection(.deviceIndependentFlagsMask).isEmpty, window?.firstResponder === self,
           !hasMarkedText(), selectedRanges.count == 1, selectedRange().length == 0 {
            let offset = characterIndexForInsertion(at:convert(event.locationInWindow,from:nil))
            if let edit = MarkdownListEditing.toggleTask(in:string,at:offset) {
                let selection = selectedRange()
                withUndoGroup("Toggle Checkbox") {
                    replaceSelectedLines(edit.range,with:edit.replacement,selection:selection,undoSelection:selection)
                }
                return
            }
        }
        super.mouseDown(with:event)
    }
    static let lineActions: Set<Selector> = [#selector(moveLinesUp(_:)),#selector(moveLinesDown(_:)),#selector(copyLinesUp(_:)),#selector(copyLinesDown(_:)),
        #selector(deleteLines(_:)),#selector(insertLineBelow(_:)),#selector(insertLineAbove(_:)),#selector(selectWholeLine(_:)),#selector(indentLines(_:)),#selector(outdentLines(_:))]
    @objc func moveLinesUp(_ sender: Any?) { lineEdit("Move Line Up") { MarkdownLineEditing.moveLines(in:$0,selection:$1,up:true) } }
    @objc func moveLinesDown(_ sender: Any?) { lineEdit("Move Line Down") { MarkdownLineEditing.moveLines(in:$0,selection:$1,up:false) } }
    @objc func copyLinesUp(_ sender: Any?) { lineEdit("Copy Line Up") { MarkdownLineEditing.copyLines(in:$0,selection:$1,down:false) } }
    @objc func copyLinesDown(_ sender: Any?) { lineEdit("Copy Line Down") { MarkdownLineEditing.copyLines(in:$0,selection:$1,down:true) } }
    @objc func deleteLines(_ sender: Any?) { lineEdit("Delete Line") { MarkdownLineEditing.deleteLines(in:$0,selection:$1) } }
    @objc func insertLineBelow(_ sender: Any?) { lineEdit("Insert Line Below") { MarkdownLineEditing.insertLine(in:$0,selection:$1,above:false) } }
    @objc func insertLineAbove(_ sender: Any?) { lineEdit("Insert Line Above") { MarkdownLineEditing.insertLine(in:$0,selection:$1,above:true) } }
    @objc func indentLines(_ sender: Any?) { lineEdit("Indent") { MarkdownLineEditing.indent(in:$0,selection:$1,outdent:false) } }
    @objc func outdentLines(_ sender: Any?) { lineEdit("Outdent") { MarkdownLineEditing.indent(in:$0,selection:$1,outdent:true) } }
    @objc func selectWholeLine(_ sender: Any?) {
        guard !hasMarkedText(), selectedRanges.count == 1, let range = MarkdownLineEditing.selectLine(in:string,selection:selectedRange()) else { return }
        setSelectedRange(range); scrollRangeToVisible(range)
    }
    private func lineEdit(_ name: String, _ make: (String,NSRange) -> MarkdownLineEditing.Edit?) {
        guard isEditable, !hasMarkedText(), selectedRanges.count == 1, let edit = make(string,selectedRange()),
              (string as NSString).substring(with:edit.range) != edit.replacement else { return }
        withUndoGroup(name) {
            replaceSelectedLines(edit.range,with:edit.replacement,selection:edit.selection,undoSelection:selectedRange())
        }
        scrollRangeToVisible(edit.selection)
    }
    override func keyDown(with event: NSEvent) {
        if event.modifierFlags.intersection(.deviceIndependentFlagsMask) == [.command,.shift], event.charactersIgnoringModifiers?.lowercased() == "f" { globalSearch?(); return }
        super.keyDown(with:event)
    }

    // MARK: Pasteboard and drops

    override func paste(_ sender: Any?) {
        if consume(NSPasteboard.general) { return }; super.paste(sender)
    }
    override var writablePasteboardTypes: [NSPasteboard.PasteboardType] {
        selectedRanges.contains(where:{ $0.rangeValue.length > 0 }) ? [.string] : []
    }
    override func writeSelection(to pasteboard: NSPasteboard, type: NSPasteboard.PasteboardType) -> Bool {
        guard type == .string else { return super.writeSelection(to:pasteboard,type:type) }
        // Copy the exact Markdown source of every selected range.
        let source = string as NSString
        let ranges = selectedRanges.map(\.rangeValue)
        guard ranges.contains(where:{ $0.length > 0 }), ranges.allSatisfy({ $0.location != NSNotFound && NSMaxRange($0) <= source.length }) else { return false }
        return pasteboard.setString(ranges.map { source.substring(with:$0) }.joined(separator:"\n"),forType:.string)
    }
    override func draggingEntered(_ sender: any NSDraggingInfo) -> NSDragOperation {
        if sender.draggingPasteboard.canReadObject(forClasses:[NSURL.self],options:[.urlReadingFileURLsOnly:true]) || sender.draggingPasteboard.availableType(from:[.png,.tiff]) != nil { return .copy }
        return super.draggingEntered(sender)
    }
    override func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        let pasteboard = sender.draggingPasteboard
        let external = pasteboard.canReadObject(forClasses:[NSURL.self],options:[.urlReadingFileURLsOnly:true]) || pasteboard.availableType(from:[.png,.tiff]) != nil
        guard external else { return super.performDragOperation(sender) }
        let point = convert(sender.draggingLocation,from:nil)
        setSelectedRange(NSRange(location:characterIndexForInsertion(at:point),length:0))
        if consume(pasteboard) { return true }; return super.performDragOperation(sender)
    }
    func consume(_ pasteboard: NSPasteboard) -> Bool {
        if let urls = pasteboard.readObjects(forClasses:[NSURL.self],options:[.urlReadingFileURLsOnly:true]) as? [URL] {
            if !urls.isEmpty, let onFile {
                for url in urls { onFile(url) }; return true
            }
        }
        if let data = pasteboard.data(forType:.png) { onImage?(data,"Screenshot.png","image/png"); return true }
        if let data = pasteboard.data(forType:.tiff) {
            onImage?(data,"Screenshot.tiff","image/tiff"); return true
        }
        return false
    }
}
