import AppKit
import MemoizeCore

@MainActor final class LibraryWindow: NSObject, NSTableViewDataSource, NSTableViewDelegate, NSSearchFieldDelegate, NSWindowDelegate {
    let window: NSWindow
    let search = NSSearchField()
    private let tags = NSButton()
    private let tagMenu = NSMenu()
    let table = NSTableView()
    let status = NSTextField(labelWithString:"")
    private let empty = NSTextField(wrappingLabelWithString:"No notes yet\n⌘N")
    var notes: [NoteSummary] = []
    var onSearch: ((String,String?) -> Void)?
    var onSelect: ((String) -> Void)?
    var onNew: (() -> Void)?
    var onSettings: (() -> Void)?
    var onShow: (() -> Void)?
    var onVisibilityChange: ((Bool) -> Void)?
    private(set) var selectedTag: String?
    private var displayedQuery = ""

    override init() {
        window = NSWindow(contentRect:NSRect(x:80,y:170,width:370,height:630),styleMask:[.titled,.closable,.miniaturizable,.resizable],backing:.buffered,defer:false)
        super.init()
        window.title = "Memoize"; window.minSize = NSSize(width:310,height:330); window.isReleasedWhenClosed = false
        window.delegate = self
        if !ProcessInfo.processInfo.arguments.contains("--smoke-test") { window.setFrameAutosaveName("Library") }
        window.collectionBehavior = [.canJoinAllSpaces,.fullScreenAuxiliary,.canJoinAllApplications,.participatesInCycle]
        let root = WindowBackgroundView(); window.contentView = root
        let header = NSStackView(); header.orientation = .horizontal; header.spacing = 8; header.alignment = .centerY
        search.placeholderString = "Search all notes"; search.delegate = self; search.sendsSearchStringImmediately = true
        search.translatesAutoresizingMaskIntoConstraints = false; header.addArrangedSubview(search)
        search.setContentHuggingPriority(.defaultLow,for:.horizontal)
        search.setContentCompressionResistancePriority(.defaultLow,for:.horizontal)
        tags.target = self; tags.action = #selector(showTags)
        tags.bezelStyle = .accessoryBarAction; tags.isBordered = false; tags.imagePosition = .imageOnly
        tags.image = NSImage(systemSymbolName:"line.3.horizontal.decrease",accessibilityDescription:"Filter tags")
        tags.toolTip = "Filter tags"; tags.setAccessibilityLabel("Filter tags")
        tags.widthAnchor.constraint(equalToConstant:32).isActive = true; header.addArrangedSubview(tags)
        let new = NSButton(image:NSImage(systemSymbolName:"square.and.pencil",accessibilityDescription:"New note")!,target:self,action:#selector(newAction))
        new.bezelStyle = .accessoryBarAction; new.isBordered = false; new.toolTip = "New note (⌘N)"; header.addArrangedSubview(new)
        root.addSubview(header); header.translatesAutoresizingMaskIntoConstraints = false

        let column = NSTableColumn(identifier:.init("Note")); table.addTableColumn(column); table.headerView = nil
        table.columnAutoresizingStyle = .uniformColumnAutoresizingStyle; table.intercellSpacing = NSSize(width:0,height:6)
        table.rowHeight = 76; table.style = .plain; table.backgroundColor = .windowBackgroundColor
        table.autoresizingMask = [.width]
        table.dataSource = self; table.delegate = self; table.target = self; table.action = #selector(openSelection)
        table.setAccessibilityLabel("Notes by creation date")
        let scroll = NSScrollView(); scroll.documentView = table; scroll.hasVerticalScroller = true; scroll.autohidesScrollers = true; scroll.drawsBackground = false
        scroll.translatesAutoresizingMaskIntoConstraints = false; root.addSubview(scroll)
        status.font = .systemFont(ofSize:11); status.textColor = .secondaryLabelColor; status.lineBreakMode = .byTruncatingTail
        status.translatesAutoresizingMaskIntoConstraints = false; root.addSubview(status)
        empty.textColor = .secondaryLabelColor; empty.alignment = .center; empty.translatesAutoresizingMaskIntoConstraints = false; root.addSubview(empty)
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo:root.topAnchor,constant:16),header.leadingAnchor.constraint(equalTo:root.leadingAnchor,constant:16),header.trailingAnchor.constraint(equalTo:root.trailingAnchor,constant:-16),
            scroll.topAnchor.constraint(equalTo:header.bottomAnchor,constant:12),scroll.leadingAnchor.constraint(equalTo:root.leadingAnchor,constant:6),scroll.trailingAnchor.constraint(equalTo:root.trailingAnchor,constant:-6),scroll.bottomAnchor.constraint(equalTo:status.topAnchor,constant:-10),
            status.leadingAnchor.constraint(equalTo:root.leadingAnchor,constant:18),status.trailingAnchor.constraint(equalTo:root.trailingAnchor,constant:-18),status.bottomAnchor.constraint(equalTo:root.bottomAnchor,constant:-12),
            empty.centerXAnchor.constraint(equalTo:scroll.centerXAnchor),empty.centerYAnchor.constraint(equalTo:scroll.centerYAnchor),empty.widthAnchor.constraint(equalToConstant:270)
        ])
        clampToScreen(window)
    }
    func update(_ notes: [NoteSummary], tagValues: [String], selected: String?, query: String = "") {
        let changed = self.notes != notes || displayedQuery != query; self.notes = notes; displayedQuery = query
        if changed { table.reloadData() }
        empty.isHidden = !notes.isEmpty
        empty.stringValue = search.stringValue.isEmpty && selectedTag == nil ? "No notes yet\n⌘N" : "No matches"
        if let selected, let index = notes.firstIndex(where:{ $0.id == selected }) { table.selectRowIndexes(IndexSet(integer:index),byExtendingSelection:false) }
        let newTags = uniqueTags(tagValues.flatMap { tag -> [String] in
            var parts: [String] = []; var prefix = ""
            for part in tag.split(separator:"/") { prefix = prefix.isEmpty ? String(part) : prefix + "/" + part; parts.append(prefix) }; return parts
        })
        if tagMenu.items.dropFirst().compactMap({ ($0.representedObject as? String).map { Data($0.utf8) } }) != newTags.map({ Data($0.utf8) }) || tagMenu.items.isEmpty {
            tagMenu.removeAllItems()
            for tag in [nil] + newTags.map(Optional.some) {
                let item = NSMenuItem(title:tag.map { "#" + $0 } ?? "All tags",action:#selector(filterTag(_:)),keyEquivalent:"")
                item.target = self; item.representedObject = tag; tagMenu.addItem(item)
            }
        }
        updateFilterAppearance()
    }
    func show(searching: Bool = false, focus: Bool = true) {
        if NSApp.isHidden { NSApp.unhide(nil) }
        if focus { onShow?() }
        clampToScreen(window)
        if focus { NSApp.activate(); window.makeKeyAndOrderFront(nil) }
        else { window.orderFront(nil) }
        if searching { window.makeFirstResponder(search) }
        onVisibilityChange?(true)
    }
    func hide() { window.orderOut(nil); onVisibilityChange?(false) }
    func windowDidMiniaturize(_ notification: Notification) { onVisibilityChange?(false) }
    func windowDidDeminiaturize(_ notification: Notification) { onVisibilityChange?(true) }
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        hide()
        return false
    }
    func filter(_ tag: String) {
        selectedTag = tag; search.stringValue = ""; updateFilterAppearance(); onSearch?("",selectedTag); show()
    }
    func numberOfRows(in tableView: NSTableView) -> Int { notes.count }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let identifier = NSUserInterfaceItemIdentifier("NoteCell")
        let cell = tableView.makeView(withIdentifier:identifier,owner:self) as? NoteCell ?? NoteCell()
        cell.identifier = identifier; cell.display(notes[row],query:displayedQuery); return cell
    }
    func tableView(_ tableView: NSTableView, rowViewForRow row: Int) -> NSTableRowView? { NoteRow() }
    func controlTextDidChange(_ obj: Notification) { onSearch?(search.stringValue,selectedTag) }
    private func updateFilterAppearance() {
        tags.image = NSImage(systemSymbolName:selectedTag == nil ? "line.3.horizontal.decrease" : "line.3.horizontal.decrease.circle.fill",accessibilityDescription:"Filter tags")
        tags.contentTintColor = selectedTag == nil ? .secondaryLabelColor : .controlAccentColor
        tags.toolTip = selectedTag.map { "#" + $0 } ?? "Filter tags"
        tags.setAccessibilityValue(selectedTag.map { "#" + $0 } ?? "All tags")
        for item in tagMenu.items {
            let value = (item.representedObject as? String).map { Data($0.utf8) }
            item.state = value == selectedTag.map { Data($0.utf8) } ? .on : .off
        }
        search.placeholderString = selectedTag.map { "Search #" + $0 } ?? "Search all notes"
    }
    @objc private func showTags() { tagMenu.popUp(positioning:nil,at:NSPoint(x:0,y:tags.bounds.maxY + 4),in:tags) }
    @objc private func filterTag(_ item: NSMenuItem) { selectedTag = item.representedObject as? String; updateFilterAppearance(); onSearch?(search.stringValue,selectedTag) }
    @objc private func openSelection() { if notes.indices.contains(table.selectedRow) { onSelect?(notes[table.selectedRow].id) } }
    @objc private func newAction() { onNew?() }
    @objc private func settingsAction() { onSettings?() }
}

final class WindowBackgroundView: NSView {
    override var isOpaque: Bool { true }
    override func draw(_ dirtyRect: NSRect) { NSColor.windowBackgroundColor.setFill(); bounds.intersection(dirtyRect).fill() }
}

private final class NoteRow: NSTableRowView {
    override var interiorBackgroundStyle: NSView.BackgroundStyle { .normal }
    override func drawSelection(in dirtyRect: NSRect) {
        NSColor.controlAccentColor.withAlphaComponent(0.13).setFill()
        NSBezierPath(roundedRect:bounds.insetBy(dx:6,dy:1),xRadius:9,yRadius:9).fill()
    }
}

private final class NoteCell: NSTableCellView {
    private var title = ""
    private var preview = ""
    private var date = ""
    private var stateSymbol: String?
    private var query = ""
    override var isFlipped: Bool { true }
    func display(_ note: NoteSummary, query: String) {
        self.query = query
        let lines = note.preview.split(whereSeparator:\.isNewline)
        title = (lines.first.map(String.init) ?? "Empty note").replacingOccurrences(of:#"^#{1,6}\s+"#,with:"",options:.regularExpression)
        preview = note.excerpt?.split(whereSeparator:\.isNewline).joined(separator:" ") ?? lines.dropFirst().joined(separator:" ")
        date = Calendar.current.isDateInToday(note.created) ? note.created.formatted(date:.omitted,time:.shortened) : note.created.formatted(.dateTime.month(.abbreviated).day())
        stateSymbol = note.error != nil ? "exclamationmark.circle" : note.conflict ? "doc.on.doc" : note.pending ? "arrow.triangle.2.circlepath" : note.state == "ARCHIVED" ? "archivebox" : nil
        toolTip = note.error ?? (note.conflict ? "Conflict copy" : note.state == "ARCHIVED" ? "Archived" : note.created.formatted(date:.complete,time:.shortened)); needsDisplay = true
        setAccessibilityElement(true); setAccessibilityRole(.staticText); setAccessibilityLabel(title + ". " + preview + ". " + date)
    }
    override func draw(_ dirtyRect: NSRect) {
        let paragraph = NSMutableParagraphStyle(); paragraph.lineBreakMode = .byTruncatingTail
        let dateAttributes: [NSAttributedString.Key:Any] = [.font:NSFont.systemFont(ofSize:10),.foregroundColor:NSColor.secondaryLabelColor]
        let dateWidth = (date as NSString).size(withAttributes:dateAttributes).width
        highlighted(title,attributes:[.font:NSFont.systemFont(ofSize:13,weight:.medium),.foregroundColor:NSColor.labelColor,.paragraphStyle:paragraph]).draw(in:NSRect(x:16,y:12,width:max(0,bounds.width - dateWidth - 44),height:20))
        (date as NSString).draw(at:NSPoint(x:bounds.width - dateWidth - 16,y:14),withAttributes:dateAttributes)
        paragraph.lineSpacing = 2; paragraph.lineBreakMode = .byWordWrapping
        highlighted(preview,attributes:[.font:NSFont.systemFont(ofSize:12),.foregroundColor:NSColor.secondaryLabelColor,.paragraphStyle:paragraph]).draw(with:NSRect(x:16,y:34,width:max(0,bounds.width - 48),height:34),options:[.usesLineFragmentOrigin,.truncatesLastVisibleLine])
        if let stateSymbol, let image = NSImage(systemSymbolName:stateSymbol,accessibilityDescription:toolTip) {
            image.draw(in:NSRect(x:bounds.width - 29,y:48,width:12,height:12),from:.zero,operation:.sourceOver,fraction:0.5,respectFlipped:true,hints:nil)
        }
    }
    private func highlighted(_ text: String, attributes: [NSAttributedString.Key:Any]) -> NSAttributedString {
        let result = NSMutableAttributedString(string:text,attributes:attributes)
        for range in SearchText.ranges(in:text,query:query) {
            result.addAttributes([.backgroundColor:NSColor.systemYellow.withAlphaComponent(0.35),.foregroundColor:NSColor.labelColor],range:range)
        }
        return result
    }
}
