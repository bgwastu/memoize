import AppKit
import QuickLookUI
import QuartzCore
import KeyboardShortcuts
import MemoizeCore

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
    private let paragraph: NSParagraphStyle = { let style = NSMutableParagraphStyle(); style.lineSpacing = 3; return style }()
    private var baseAttributes: [NSAttributedString.Key:Any] { [.font:baseFont,.paragraphStyle:paragraph,.foregroundColor:NSColor.labelColor] }

    override init() {
        panel = FloatingPanel(contentRect:NSRect(x:260,y:180,width:530,height:590),styleMask:[.titled,.closable,.miniaturizable,.resizable,.fullSizeContentView],backing:.buffered,defer:false)
        textView = SourceTextView.make()
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
        textView.defaultParagraphStyle = paragraph; textView.typingAttributes = baseAttributes
        // Links are underlined by the styler; tags stay plain.
        textView.linkTextAttributes = [.foregroundColor:NSColor.controlAccentColor]
        textView.textContainerInset = NSSize(width:22,height:6); textView.isVerticallyResizable = true; textView.isHorizontallyResizable = false
        textView.autoresizingMask = [.width]; textView.textContainer?.widthTracksTextView = true
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
        textView.textStorage?.setAttributedString(NSAttributedString(string:note.content,attributes:baseAttributes))
        textView.typingAttributes = baseAttributes
        textView.ownUndoManager.enableUndoRegistration()
        textView.breakUndoCoalescing()
        let length = (note.content as NSString).length
        let position = min(max(0,note.cursor),length)
        textView.setSelectedRange(NSRange(location:position,length:min(max(0,note.selectionLength),length - position)))
        panel.title = note.conflict ? "Memoize — conflict copy" : "Memoize"
        installing = false
        // Small notes get their list indents before the saved scroll position is restored.
        highlight()
        if let layout = textView.layoutManager, let container = textView.textContainer { layout.ensureLayout(for:container) }
        textView.sizeToFit()
        let maxY = max(0,textView.frame.height - scrollView.contentView.bounds.height)
        scrollView.contentView.scroll(to:NSPoint(x:0,y:min(max(0,note.scroll),maxY))); scrollView.reflectScrolledClipView(scrollView.contentView)
    }
    /// Merges a newer saved or synced version into the open note. Only the
    /// changed span is replaced, so the caret, selection, and viewport stay put.
    /// The merge is one Undo step, keeping earlier Undo history valid.
    func merge(_ note: Note) {
        guard note.id == displayedID, let storage = textView.textStorage, !textView.hasMarkedText() else { return }
        panel.title = note.conflict ? "Memoize — conflict copy" : "Memoize"
        let current = textView.source
        guard !sameSource(current,note.content) else { return }
        let old = current as NSString, new = note.content as NSString
        var prefix = 0; let shortest = min(old.length,new.length)
        while prefix < shortest, old.character(at:prefix) == new.character(at:prefix) { prefix += 1 }
        var suffix = 0
        while suffix < shortest - prefix, old.character(at:old.length - 1 - suffix) == new.character(at:new.length - 1 - suffix) { suffix += 1 }
        // Never split a surrogate pair or a CRLF.
        while prefix > 0, UTF16.isLeadSurrogate(old.character(at:prefix - 1)) || (old.character(at:prefix - 1) == 13 && prefix < old.length && old.character(at:prefix) == 10) { prefix -= 1 }
        while suffix > 0, UTF16.isTrailSurrogate(old.character(at:old.length - suffix)) { suffix -= 1 }
        let replaced = NSRange(location:prefix,length:old.length - prefix - suffix)
        let replacement = new.substring(with:NSRange(location:prefix,length:new.length - prefix - suffix))
        let delta = replacement.utf16.count - replaced.length
        func map(_ location: Int) -> Int {
            if location <= replaced.location { return location }
            if location >= NSMaxRange(replaced) { return location + delta }
            return replaced.location + replacement.utf16.count
        }
        let selections = textView.selectedRanges.map(\.rangeValue).map { range -> NSValue in
            let start = map(range.location); let end = max(start,map(NSMaxRange(range)))
            return NSValue(range:NSRange(location:start,length:end - start))
        }
        publishedSource = note.content
        preservingViewport(map:map) {
            textView.withUndoGroup("Sync Change") {
                guard textView.shouldChangeText(in:replaced,replacementString:replacement) else { return }
                storage.replaceCharacters(in:replaced,with:NSAttributedString(string:replacement,attributes:baseAttributes))
                textView.didChangeText()
            }
            textView.selectedRanges = selections
        }
        generation += 1; highlight()
    }
    /// Keeps the first visible character at the same height across a reflow.
    private func preservingViewport(map: (Int) -> Int = { $0 }, _ body: () -> Void) {
        guard let layout = textView.layoutManager, let container = textView.textContainer, layout.numberOfGlyphs > 0 else { body(); return }
        let origin = textView.textContainerOrigin
        let top = scrollView.contentView.bounds.minY
        let glyph = layout.glyphIndex(for:NSPoint(x:0,y:max(0,top - origin.y)),in:container)
        let anchor = layout.characterIndexForGlyph(at:glyph)
        let before = layout.lineFragmentRect(forGlyphAt:glyph,effectiveRange:nil).minY
        body()
        let length = textView.textStorage?.length ?? 0
        guard length > 0 else { return }
        let moved = layout.glyphIndexForCharacter(at:min(map(anchor),length - 1))
        let after = layout.lineFragmentRect(forGlyphAt:moved,effectiveRange:nil).minY
        guard after != before else { return }
        textView.sizeToFit()
        let maxY = max(0,textView.frame.height - scrollView.contentView.bounds.height)
        scrollView.contentView.scroll(to:NSPoint(x:scrollView.contentView.bounds.minX,y:min(max(0,top + after - before),maxY)))
        scrollView.reflectScrolledClipView(scrollView.contentView)
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
        guard !installing else { return }
        let source = textView.source
        guard !sameSource(source,publishedSource) else { return }; generation += 1
        publishedSource = source
        onEdit?(source); highlight(source)
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
    private func highlight(_ text: String? = nil) {
        parsing?.cancel()
        guard !textView.hasMarkedText() else { return }
        let source = text ?? textView.source; let revision = generation
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
        onReferences?(result)
    }
    private func apply(_ result: MarkdownAnalysis) {
        // Indents can reflow wrapped list lines above the viewport; keep the reading position.
        preservingViewport { MarkdownStyler.apply(result,to:textView,paragraph:paragraph) }
    }
    func textView(_ textView: NSTextView, shouldChangeTypingAttributes oldTypingAttributes: [String:Any] = [:], toAttributes newTypingAttributes: [NSAttributedString.Key:Any] = [:]) -> [NSAttributedString.Key:Any] {
        // Typing never inherits a link or a list indent; the styler derives both from the source.
        var attributes = newTypingAttributes
        attributes[.link] = nil; attributes[.toolTip] = nil; attributes[.paragraphStyle] = paragraph
        return attributes
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
