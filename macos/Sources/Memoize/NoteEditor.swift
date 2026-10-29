import AppKit
import QuickLookUI
import QuartzCore
import KeyboardShortcuts
import MemoizeCore

final class SourceTextView: NSTextView {
    lazy var noteFinder = NoteFinder(textView:self)
    override func performTextFinderAction(_ sender: Any?) {
        let tag = (sender as? NSMenuItem)?.tag ?? (sender as? NSControl)?.tag ?? NSTextFinder.Action.showFindInterface.rawValue
        if let action = NSTextFinder.Action(rawValue:tag) { noteFinder.finder.performAction(action) }
    }
    override func validateUserInterfaceItem(_ item: any NSValidatedUserInterfaceItem) -> Bool {
        if item.action == #selector(performTextFinderAction(_:)), let action = NSTextFinder.Action(rawValue:item.tag) {
            return noteFinder.finder.validateAction(action)
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
    var onImage: ((Data,String,String) -> Void)?
    var onFile: ((URL) -> Void)?
    var navigate: ((Int) -> Void)?
    var globalSearch: (() -> Void)?
    /// Return true when the empty note was handled. Return false to delete a remaining character.
    var onEmptyBackspace: (() -> Bool)?
    override func deleteBackward(_ sender: Any?) {
        if !hasMarkedText(), string.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty, let onEmptyBackspace, onEmptyBackspace() {
            return
        }
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
    override func keyDown(with event: NSEvent) {
        if event.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command {
            if event.charactersIgnoringModifiers == "[" { navigate?(1); return }
            if event.charactersIgnoringModifiers == "]" { navigate?(-1); return }
        }
        if event.modifierFlags.intersection(.deviceIndependentFlagsMask) == [.command,.shift], event.charactersIgnoringModifiers?.lowercased() == "f" { globalSearch?(); return }
        super.keyDown(with:event)
    }
    override func paste(_ sender: Any?) {
        if consume(NSPasteboard.general) { return }; super.paste(sender)
    }
    override var writablePasteboardTypes: [NSPasteboard.PasteboardType] {
        selectedRanges.contains(where:{ $0.rangeValue.length > 0 }) ? [.string] : []
    }
    override func writeSelection(to pasteboard: NSPasteboard, type: NSPasteboard.PasteboardType) -> Bool {
        guard type == .string else { return super.writeSelection(to:pasteboard,type:type) }
        // TextKit 2's plain-text copy can omit a selection. Copy its source directly.
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
        let point = convert(sender.draggingLocation,from:nil)
        setSelectedRange(NSRange(location:characterIndexForInsertion(at:point),length:0))
        if consume(sender.draggingPasteboard) { return true }; return super.performDragOperation(sender)
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

@MainActor final class NoteEditor: NSObject, NSTextViewDelegate, @MainActor NSTextStorageDelegate, NSWindowDelegate, NSPopoverDelegate {
    let panel: FloatingPanel
    let textView: SourceTextView
    let scrollView = NSScrollView()
    let header = HoverHeader(frame:.zero)
    let toolbar = HoverToolbar(frame:.zero)
    let syncIndicator = SyncIndicator(frame:.zero)
    let updatedLabel = NSTextField(labelWithString:"")
    let toast = EditorToast(frame:.zero)
    let attachmentStrip = AttachmentStrip(frame:.zero)
    let screenShareToggle = NSButton(title:"Hidden in screen shares",target:nil,action:nil)
    private var nativeCapturePrivacy = false
    private var screenIsCaptured = false
    private let pin = NSButton()
    private(set) var libraryToggle = NSButton()
    private var parsing: Task<Void,Never>?
    private let markdown = MarkdownWorker()
    private var preview: NSPopover?
    var isShowingAttachmentPreview: Bool { preview?.isShown == true }
    private var previewView: QLPreviewView?
    private var analysis: MarkdownAnalysis?
    private var generation = 0
    private(set) var highlightedGeneration = 0
    private var installing = false
    private var undoManagers: [String:UndoManager] = [:]
    private var undoContents: [String:String] = [:]
    private var displayedID: String?
    private var publishedSource = ""
    var onEdit: ((String) -> Void)?
    var onTextReplacement: ((String,NSRange,Int) -> Void)?
    var onPosition: (() -> Void)?
    var onHide: (() -> Void)?
    var onTag: ((String) -> Void)?
    var onReferences: ((MarkdownAnalysis) -> Void)?
    var onNew: (() -> Void)?
    var onLibrary: (() -> Void)?
    var onDelete: (() -> Void)?
    var onPin: ((Bool) -> Void)?
    let baseFont = NSFont.monospacedSystemFont(ofSize:14,weight:.regular)

    override init() {
        panel = FloatingPanel(contentRect:NSRect(x:260,y:180,width:530,height:590),styleMask:[.titled,.closable,.miniaturizable,.resizable,.fullSizeContentView],backing:.buffered,defer:false)
        textView = SourceTextView(usingTextLayoutManager:true)
        super.init()
        panel.title = "Memoize"; panel.titleVisibility = .hidden; panel.titlebarAppearsTransparent = true
        panel.minSize = NSSize(width:330,height:260); panel.isReleasedWhenClosed = false; panel.hidesOnDeactivate = false
        panel.allowsToolTipsWhenApplicationIsInactive = true
        panel.collectionBehavior = [.canJoinAllSpaces,.fullScreenAuxiliary,.canJoinAllApplications,.participatesInCycle]
        panel.isFloatingPanel = true; panel.delegate = self
        if !ProcessInfo.processInfo.arguments.contains("--smoke-test") { panel.setFrameAutosaveName("FloatingNote") }
        setPinned(UserDefaults.standard.object(forKey:"pinned") as? Bool ?? true)
        let content = WindowBackgroundView(); panel.contentView = content
        header.translatesAutoresizingMaskIntoConstraints = false; content.addSubview(header)
        NSLayoutConstraint.activate([header.leadingAnchor.constraint(equalTo:content.leadingAnchor),header.trailingAnchor.constraint(equalTo:content.trailingAnchor),header.topAnchor.constraint(equalTo:content.topAnchor),header.heightAnchor.constraint(equalToConstant:28)])
        screenShareToggle.bezelStyle = .inline; screenShareToggle.isBordered = false
        screenShareToggle.font = .systemFont(ofSize:11,weight:.medium)
        screenShareToggle.imagePosition = .imageLeading
        screenShareToggle.isEnabled = false; screenShareToggle.isHidden = true
        screenShareToggle.translatesAutoresizingMaskIntoConstraints = false; header.glass.addSubview(screenShareToggle)
        NSLayoutConstraint.activate([screenShareToggle.trailingAnchor.constraint(equalTo:header.glass.trailingAnchor,constant:-12),screenShareToggle.topAnchor.constraint(equalTo:header.glass.topAnchor,constant:3),screenShareToggle.heightAnchor.constraint(equalToConstant:22),screenShareToggle.leadingAnchor.constraint(greaterThanOrEqualTo:header.glass.leadingAnchor,constant:84)])
        header.onVisibilityChange = { [weak self] revealed in self?.updateHeaderVisibility(revealed:revealed) }
        updateScreenShareControl()
        updateHeaderVisibility(revealed:false)
        let layout = NSStackView(); layout.orientation = .vertical; layout.spacing = 0; layout.alignment = .leading
        layout.translatesAutoresizingMaskIntoConstraints = false; content.addSubview(layout)
        NSLayoutConstraint.activate([layout.leadingAnchor.constraint(equalTo:content.leadingAnchor),layout.trailingAnchor.constraint(equalTo:content.trailingAnchor),layout.topAnchor.constraint(equalTo:content.topAnchor,constant:28),layout.bottomAnchor.constraint(equalTo:content.bottomAnchor)])

        scrollView.hasVerticalScroller = true; scrollView.autohidesScrollers = true; scrollView.drawsBackground = false
        scrollView.borderType = .noBorder; scrollView.documentView = textView; scrollView.translatesAutoresizingMaskIntoConstraints = false
        // The hover header already reserves space for the native window controls.
        scrollView.automaticallyAdjustsContentInsets = false
        scrollView.wantsLayer = true
        scrollView.layer?.masksToBounds = true
        textView.isRichText = false; textView.importsGraphics = false; textView.allowsUndo = true
        textView.isAutomaticQuoteSubstitutionEnabled = false; textView.isAutomaticDashSubstitutionEnabled = false
        textView.isAutomaticTextReplacementEnabled = false; textView.isAutomaticLinkDetectionEnabled = false
        textView.isContinuousSpellCheckingEnabled = true
        textView.isAutomaticSpellingCorrectionEnabled = true
        textView.font = baseFont; textView.textColor = .labelColor; textView.backgroundColor = .textBackgroundColor
        let paragraph = NSMutableParagraphStyle(); paragraph.lineSpacing = 3
        textView.defaultParagraphStyle = paragraph; textView.typingAttributes = [.font:baseFont,.paragraphStyle:paragraph,.foregroundColor:NSColor.labelColor]
        textView.linkTextAttributes = [.foregroundColor:NSColor.controlAccentColor,.underlineStyle:0]
        textView.textContainerInset = NSSize(width:22,height:6); textView.isVerticallyResizable = true; textView.isHorizontallyResizable = false
        textView.autoresizingMask = [.width]; textView.textContainer?.widthTracksTextView = true
        textView.textContainer?.containerSize = NSSize(width:490,height:CGFloat.greatestFiniteMagnitude)
        textView.minSize = NSSize(width:0,height:0); textView.maxSize = NSSize(width:CGFloat.greatestFiniteMagnitude,height:CGFloat.greatestFiniteMagnitude)
        textView.delegate = self; textView.textStorage?.delegate = self; textView.registerForDraggedTypes([.fileURL,.png,.tiff])
        NotificationCenter.default.addObserver(self,selector:#selector(undoChanged(_:)),name:.NSUndoManagerDidUndoChange,object:nil)
        NotificationCenter.default.addObserver(self,selector:#selector(undoChanged(_:)),name:.NSUndoManagerDidRedoChange,object:nil)
        layout.addArrangedSubview(scrollView); scrollView.widthAnchor.constraint(equalTo:layout.widthAnchor).isActive = true

        layout.addArrangedSubview(attachmentStrip); attachmentStrip.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([attachmentStrip.widthAnchor.constraint(equalTo:layout.widthAnchor),attachmentStrip.heightAnchor.constraint(equalToConstant:AttachmentStrip.height)])
        let footer = NSStackView(); footer.orientation = .horizontal; footer.alignment = .centerY; footer.spacing = 10; footer.edgeInsets = NSEdgeInsets(top:0,left:14,bottom:6,right:14)
        toolbar.glass.contentView = footer; layout.addArrangedSubview(toolbar); toolbar.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([toolbar.widthAnchor.constraint(equalTo:layout.widthAnchor),toolbar.heightAnchor.constraint(equalToConstant:30)])
        // The status stays outside the glass, so hiding the controls never hides it.
        // Its center follows the notes button: the bar's bottom inset would otherwise leave the cloud and date low.
        toolbar.addSubview(syncIndicator); syncIndicator.translatesAutoresizingMaskIntoConstraints = false
        updatedLabel.font = .systemFont(ofSize:11); updatedLabel.textColor = .secondaryLabelColor
        updatedLabel.lineBreakMode = .byTruncatingTail; updatedLabel.maximumNumberOfLines = 1
        updatedLabel.usesSingleLineMode = true; updatedLabel.cell?.truncatesLastVisibleLine = true
        updatedLabel.setContentCompressionResistancePriority(.defaultLow,for:.horizontal)
        updatedLabel.translatesAutoresizingMaskIntoConstraints = false; toolbar.addSubview(updatedLabel)
        setLastUpdated(nil)
        let spacer = NSView(); spacer.setContentHuggingPriority(.defaultLow,for:.horizontal); footer.addArrangedSubview(spacer)
        libraryToggle = button("list.bullet",help:"Show notes",action:#selector(library))
        libraryToggle.setButtonType(.toggle)
        footer.addArrangedSubview(libraryToggle)
        footer.addArrangedSubview(button("square.and.pencil",help:"New note (⌘N)",action:#selector(newNote)))
        pin.image = NSImage(systemSymbolName:"pin.fill",accessibilityDescription:"Pin note above other windows")
        pin.bezelStyle = .inline; pin.isBordered = false; pin.target = self; pin.action = #selector(togglePin); pin.toolTip = "Keep note on top"
        footer.addArrangedSubview(pin)
        footer.addArrangedSubview(button("trash",help:"Delete note",action:#selector(deleteNote)))
        NSLayoutConstraint.activate([
            syncIndicator.leadingAnchor.constraint(equalTo:toolbar.leadingAnchor,constant:12),
            syncIndicator.centerYAnchor.constraint(equalTo:libraryToggle.centerYAnchor),
            syncIndicator.widthAnchor.constraint(equalToConstant:24),syncIndicator.heightAnchor.constraint(equalToConstant:24),
            updatedLabel.leadingAnchor.constraint(equalTo:syncIndicator.trailingAnchor,constant:4),
            updatedLabel.centerYAnchor.constraint(equalTo:libraryToggle.centerYAnchor),
            updatedLabel.trailingAnchor.constraint(lessThanOrEqualTo:toolbar.trailingAnchor,constant:-130)
        ])
        content.addSubview(toast); toast.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([toast.centerXAnchor.constraint(equalTo:content.centerXAnchor),toast.bottomAnchor.constraint(equalTo:scrollView.bottomAnchor,constant:-8),toast.heightAnchor.constraint(equalToConstant:32),toast.leadingAnchor.constraint(greaterThanOrEqualTo:content.leadingAnchor,constant:14),toast.trailingAnchor.constraint(lessThanOrEqualTo:content.trailingAnchor,constant:-14)])
        updatePinImage()
        textView.setAccessibilityLabel("Markdown note")
        clampToScreen(panel)
    }
    private func button(_ symbol: String, help: String, action: Selector) -> NSButton {
        let button = NSButton(image:NSImage(systemSymbolName:symbol,accessibilityDescription:help)!,target:self,action:action)
        button.bezelStyle = .inline; button.isBordered = false; button.toolTip = help
        button.setAccessibilityLabel(help); button.setAccessibilityHelp(help)
        return button
    }
    func display(_ note: Note, direction: Int = 0) {
        if displayedID != note.id { preview?.close() }
        // Finish the previous document's native typing group before changing managers.
        textView.breakUndoCoalescing()
        scrollView.layer?.removeAnimation(forKey:kCATransition)
        if direction != 0, panel.isVisible, !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion {
            let transition = CATransition()
            transition.type = .push
            transition.subtype = direction > 0 ? .fromLeft : .fromRight
            transition.duration = 0.16
            transition.timingFunction = CAMediaTimingFunction(name:.easeInEaseOut)
            scrollView.layer?.add(transition,forKey:kCATransition)
        }
        generation += 1; parsing?.cancel(); installing = true
        attachmentStrip.update([])
        textView.noteFinder.finder.noteClientStringWillChange()
        if let displayedID { undoContents[displayedID] = textView.string }
        if undoManagers.count > 12 { undoManagers = [:]; undoContents = [:] }
        textView.ownUndoManager = undoManagers[note.id] ?? UndoManager(); undoManagers[note.id] = textView.ownUndoManager
        // Native undo operations refer to offsets in the old document, not a remote replacement.
        if let previous = undoContents[note.id], !sameSource(previous,note.content) { textView.ownUndoManager.removeAllActions() }
        displayedID = note.id
        publishedSource = note.content
        textView.ownUndoManager.disableUndoRegistration()
        textView.string = note.content; textView.font = baseFont; textView.textColor = .labelColor
        textView.ownUndoManager.enableUndoRegistration()
        textView.breakUndoCoalescing()
        let length = (note.content as NSString).length
        let position = min(note.cursor,length)
        textView.setSelectedRange(NSRange(location:position,length:min(note.selectionLength,length - position)))
        textView.layoutSubtreeIfNeeded()
        scrollView.contentView.scroll(to:NSPoint(x:0,y:note.scroll)); scrollView.reflectScrolledClipView(scrollView.contentView)
        panel.title = note.conflict ? "Memoize — conflict copy" : "Memoize"
        installing = false; highlight()
    }
    /// Clearing a just-created note starts a fresh identity, while native Undo keeps working.
    func continueDraft(as id: String) {
        if let displayedID { undoManagers.removeValue(forKey:displayedID); undoContents.removeValue(forKey:displayedID) }
        displayedID = id; undoManagers[id] = textView.ownUndoManager
        attachmentStrip.update([])
    }
    func show() {
        clampToScreen(panel)
        if NSApp.isHidden { NSApp.unhide(nil) }
        NSApp.activate(); panel.makeKeyAndOrderFront(nil); panel.makeFirstResponder(textView)
        header.refreshHover(); toolbar.refreshHover()
    }
    func hide() {
        if let onHide { onHide() } else { panel.orderOut(nil) }
    }
    func find(query: String? = nil) { show(); textView.noteFinder.show(query:query) }
    func windowShouldClose(_ sender: NSWindow) -> Bool { hide(); return false }
    func textDidChange(_ notification: Notification) {
        guard !installing, !sameSource(textView.string,publishedSource) else { return }; generation += 1
        publishedSource = textView.string
        textView.needsDisplay = true
        onEdit?(publishedSource); highlight()
    }
    func textStorage(_ textStorage: NSTextStorage, willProcessEditing editedMask: NSTextStorageEditActions, range editedRange: NSRange, changeInLength delta: Int) {
        // Attribute fixing can broaden the later didProcessEditing range.
        guard !installing, editedMask.contains(.editedCharacters), let displayedID else { return }
        onTextReplacement?(displayedID,NSRange(location:editedRange.location,length:editedRange.length - delta),editedRange.length)
    }
    @objc private func undoChanged(_ notification: Notification) {
        guard let manager = notification.object as? UndoManager, manager === textView.ownUndoManager else { return }
        textDidChange(notification)
    }
    func textViewDidChangeSelection(_ notification: Notification) {
        guard !installing else { return }
        onPosition?()
    }
    func undoManager(for view: NSTextView) -> UndoManager? { textView.ownUndoManager }
    func windowWillReturnUndoManager(_ window: NSWindow) -> UndoManager? { textView.ownUndoManager }
    func windowDidResignKey(_ notification: Notification) { onPosition?() }
    func windowDidBecomeKey(_ notification: Notification) {
        // Window cycling should return directly to the insertion point, preserving Find focus.
        if panel.firstResponder === panel.contentView || panel.firstResponder === panel {
            panel.makeFirstResponder(textView)
        }
    }
    func textView(_ textView: NSTextView, clickedOnLink link: Any, at charIndex: Int) -> Bool {
        if let value = link as? String {
            if value.hasPrefix("memoize-tag:") { onTag?(String(value.dropFirst(12))); return true }
            let normalized = value.hasPrefix("www.") ? "https://" + value : value
            if let url = URL(string: normalized), let scheme = url.scheme?.lowercased(), ["http", "https", "mailto"].contains(scheme) {
                NSWorkspace.shared.open(url)
                return true
            }
        } else if let url = link as? URL {
            NSWorkspace.shared.open(url)
            return true
        }
        return false
    }
    private func highlight() {
        parsing?.cancel()
        guard !textView.hasMarkedText() else { return }
        let source = textView.string; let revision = generation
        // Typical notes parse in a few milliseconds; color them in the same frame as the keystroke.
        if (source as NSString).length <= 32_768 { finish(MarkdownAnalysis.parse(source),revision:revision); return }
        parsing = Task { [weak self] in
            // Coalesce edits arriving in this run-loop turn without waiting for a
            // typing pause. The serial worker skips cancelled queued requests.
            await Task.yield()
            guard !Task.isCancelled else { return }
            guard let result = await self?.markdown.analyze(source) else { return }
            guard let self, !Task.isCancelled, generation == revision, !textView.hasMarkedText() else { return }
            finish(result,revision:revision)
        }
    }
    private func finish(_ result: MarkdownAnalysis, revision: Int) {
        apply(result); analysis = result; highlightedGeneration = revision
        textView.needsDisplay = true
        onReferences?(result)
    }
    private struct StorageRun: Equatable {
        let range: NSRange; let value: String
        /// Storage reports equal neighbours as one attribute run.
        static func coalesced(_ runs: [StorageRun]) -> [StorageRun] {
            runs.reduce(into:[]) { result,run in
                if let last = result.last, last.value == run.value, NSMaxRange(last.range) == run.range.location {
                    result[result.count - 1] = StorageRun(range:NSUnionRange(last.range,run.range),value:run.value)
                } else { result.append(run) }
            }
        }
    }
    private func apply(_ result: MarkdownAnalysis) {
        // Links need AppKit hit testing. Finish storage edits before applying rendering colors.
        // Storage attributes reflow their paragraphs, so rewrite them only when they actually differ.
        if let storage = textView.textStorage {
            let fullRange = NSRange(location:0,length:storage.length)
            let paragraph = textView.defaultParagraphStyle ?? NSParagraphStyle.default
            var links: [StorageRun] = result.tags.map { StorageRun(range:$0.range,value:"memoize-tag:" + $0.value) }
            links += result.links.map { StorageRun(range:$0.range,value:$0.destination) }
            links.sort { $0.range.location < $1.range.location }
            var currentLinks: [StorageRun] = []
            storage.enumerateAttribute(.link,in:fullRange) { value,range,_ in
                if let value = value as? String { currentLinks.append(StorageRun(range:range,value:value)) }
            }
            let indents = result.listParagraphs.map { list in
                // Keep the first line's source indentation; align soft wraps after its prefix.
                StorageRun(range:list.range,value:String(Double(paragraph.firstLineHeadIndent + storage.attributedSubstring(from:list.prefix).size().width)))
            }
            var currentIndents: [StorageRun] = []
            storage.enumerateAttribute(.paragraphStyle,in:fullRange) { value,range,_ in
                if let style = value as? NSParagraphStyle, style.headIndent != paragraph.headIndent {
                    currentIndents.append(StorageRun(range:range,value:String(Double(style.headIndent))))
                }
            }
            let relink = StorageRun.coalesced(links) != currentLinks, reindent = StorageRun.coalesced(indents) != currentIndents
            if relink || reindent {
                let undo = textView.undoManager; let registered = undo?.isUndoRegistrationEnabled == true
                if registered { undo?.disableUndoRegistration() }
                storage.beginEditing()
                if relink {
                    storage.removeAttribute(.link,range:fullRange)
                    storage.removeAttribute(.underlineStyle,range:fullRange)
                    storage.removeAttribute(.toolTip,range:fullRange)
                    for tag in result.tags { storage.addAttribute(.link,value:"memoize-tag:" + tag.value,range:tag.range) }
                    for link in result.links {
                        storage.addAttribute(.link,value:link.destination,range:link.range)
                        storage.addAttribute(.underlineStyle,value:NSUnderlineStyle.single.rawValue,range:link.range)
                        storage.addAttribute(.toolTip,value:"⌘-click to open " + link.destination,range:link.range)
                    }
                }
                if reindent {
                    storage.addAttribute(.paragraphStyle,value:paragraph,range:fullRange)
                    for indent in indents {
                        let style = paragraph.mutableCopy() as! NSMutableParagraphStyle
                        style.headIndent = CGFloat(Double(indent.value)!)
                        storage.addAttribute(.paragraphStyle,value:style,range:indent.range)
                    }
                }
                storage.endEditing()
                if registered { undo?.enableUndoRegistration() }
            }
        }
        guard let manager = textView.textLayoutManager, let content = manager.textContentManager else { return }
        let start = content.documentRange.location
        manager.setRenderingAttributes([.foregroundColor:NSColor.labelColor],for:content.documentRange)
        func textRange(_ range: NSRange) -> NSTextRange? {
            guard let from = content.location(start,offsetBy:range.location), let end = content.location(from,offsetBy:range.length) else { return nil }
            return NSTextRange(location:from,end:end)
        }
        for span in result.highlights {
            guard let range = textRange(span.range) else { continue }
            let color: NSColor
            switch span.style {
            case .heading, .link, .tag: color = .controlAccentColor
            case .strong, .emphasis, .keyword: color = .systemPurple
            case .code, .quote, .comment: color = .secondaryLabelColor
            case .string: color = .systemBrown
            case .number: color = .systemBlue
            }
            manager.setRenderingAttributes([.foregroundColor:color],for:range)
        }
    }
    func showToast(_ text: String?, canUndo: Bool = false) {
        toolbar.hasToast = text != nil; toast.show(text,canUndo:canUndo)
        // Keep the insertion point above the transient overlay without resizing
        // the editor, footer, or attachment strip.
        var insets = scrollView.contentInsets; insets.bottom = text == nil ? 0 : 40; scrollView.contentInsets = insets
        if text != nil { textView.scrollRangeToVisible(textView.selectedRange()) }
    }
    func showAttachment(at url: URL, anchor: NSView) {
        guard anchor.window?.isVisible == true else { return }
        preview?.close()
        let controller = NSViewController()
        let view = QLPreviewView(frame:NSRect(x:0,y:0,width:520,height:400),style:.normal)!
        view.autostarts = false; view.shouldCloseWithWindow = false; view.previewItem = url as NSURL
        let content = WindowBackgroundView(frame:view.frame)
        view.autoresizingMask = [.width,.height]; content.addSubview(view)
        controller.view = content
        let popover = NSPopover(); popover.contentViewController = controller; popover.behavior = .transient; popover.delegate = self
        preview = popover; previewView = view
        popover.show(relativeTo:anchor.bounds,of:anchor,preferredEdge:.maxY)
    }
    func dismissAttachmentPreview() { preview?.close() }
    func updateNativeCapturePrivacy(_ enabled: Bool, captured: Bool) {
        guard nativeCapturePrivacy != enabled || screenIsCaptured != captured else { return }
        nativeCapturePrivacy = enabled; screenIsCaptured = captured; updateScreenShareControl()
    }
    private func updateScreenShareControl() {
        let visible = nativeCapturePrivacy && screenIsCaptured
        header.forceReveal = visible
        screenShareToggle.isHidden = !visible
        let title = "Hidden in screen shares"
        let help = "Turn this off in Settings → General to show notes in screen shares. macOS does not guarantee capture exclusion."
        screenShareToggle.title = title
        screenShareToggle.image = NSImage(systemSymbolName:"eye.slash",accessibilityDescription:title)
        screenShareToggle.toolTip = help; screenShareToggle.setAccessibilityLabel(title)
        screenShareToggle.setAccessibilityHelp(help)
    }
    func popoverDidClose(_ notification: Notification) {
        previewView?.close(); previewView = nil; preview = nil
    }
    func setPinned(_ value: Bool) { panel.level = value ? .floating : .normal; UserDefaults.standard.set(value,forKey:"pinned"); updatePinImage(); onPin?(value) }
    func setLibraryVisible(_ visible: Bool) {
        libraryToggle.state = visible ? .on : .off
        libraryToggle.contentTintColor = visible ? .controlAccentColor : .secondaryLabelColor
        let keys = KeyboardShortcuts.Name.toggleLibrary.shortcut?.description ?? "⌥⌘L"
        let help = (visible ? "Hide notes" : "Show notes") + " (" + keys + ")"
        libraryToggle.toolTip = help; libraryToggle.setAccessibilityLabel(help); libraryToggle.setAccessibilityHelp(help)
    }
    private func updatePinImage() {
        let pinned = panel.level == .floating
        let action = pinned ? "Unpin note" : "Keep note on top"
        pin.image = NSImage(systemSymbolName:pinned ? "pin.fill" : "pin",accessibilityDescription:action)
        pin.toolTip = action; pin.setAccessibilityLabel(action)
        pin.setAccessibilityHelp(pinned ? "Allow other windows to cover this note." : "Keep this note and its controls above other windows.")
        pin.setAccessibilityValue(pinned ? "Pinned" : "Unpinned")
    }
    @objc private func togglePin() { setPinned(panel.level != .floating); updatePinImage() }
    @objc private func library() { onLibrary?() }
    @objc private func newNote() { onNew?() }
    @objc private func deleteNote() { onDelete?() }
    func updateHeaderVisibility(revealed: Bool) {
        for type in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
            panel.standardWindowButton(type)?.isHidden = !revealed
        }
    }
    func setLastUpdated(_ date: Date?) {
        if let date {
            let compact = Calendar.current.isDateInToday(date)
                ? "Today, " + date.formatted(date:.omitted,time:.shortened)
                : Calendar.current.isDate(date,equalTo:Date(),toGranularity:.year)
                    ? date.formatted(.dateTime.month(.abbreviated).day().hour().minute())
                    : date.formatted(date:.abbreviated,time:.shortened)
            let detail = "Last updated " + date.formatted(date:.complete,time:.shortened)
            if updatedLabel.stringValue != compact { updatedLabel.stringValue = compact }
            if updatedLabel.toolTip != detail { updatedLabel.toolTip = detail }
            updatedLabel.setAccessibilityLabel(detail)
            updatedLabel.setAccessibilityValue(compact)
        } else {
            let compact = "Draft"
            let detail = "Draft — not saved yet"
            if updatedLabel.stringValue != compact { updatedLabel.stringValue = compact }
            if updatedLabel.toolTip != detail { updatedLabel.toolTip = detail }
            updatedLabel.setAccessibilityLabel(detail)
            updatedLabel.setAccessibilityValue(compact)
        }
    }
}

@MainActor func clampToScreen(_ window: NSWindow) {
    guard !NSScreen.screens.contains(where:{ $0.visibleFrame.intersects(window.frame) }), let screen = NSScreen.main else { return }
    var frame = window.frame; frame.size.width = min(frame.width,screen.visibleFrame.width); frame.size.height = min(frame.height,screen.visibleFrame.height)
    frame.origin = NSPoint(x:screen.visibleFrame.midX - frame.width / 2,y:screen.visibleFrame.midY - frame.height / 2); window.setFrame(frame,display:false)
}
