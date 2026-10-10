import AppKit
import UniformTypeIdentifiers

struct AttachedFile: Equatable {
    let id: String
    let name: String
    let mime: String
    let url: URL
    let available: Bool
}

/// File icons are cheap; file contents are loaded only when a preview is requested.
@MainActor final class AttachmentStrip: NSView {
    static let height: CGFloat = 36
    private let scroll = NSScrollView()
    private let row = NSView()
    private(set) var files: [AttachedFile] = []
    private(set) var buttons: [NSButton] = []
    var onPreview: ((AttachedFile,NSView) -> Void)?
    var onDownload: ((AttachedFile) -> Void)?
    var onRemove: ((AttachedFile) -> Void)?

    override init(frame: NSRect) {
        super.init(frame:frame)
        isHidden = true
        scroll.drawsBackground = false; scroll.borderType = .noBorder
        scroll.hasHorizontalScroller = true; scroll.autohidesScrollers = true; scroll.scrollerStyle = .overlay
        scroll.hasVerticalScroller = false
        scroll.documentView = row; addSubview(scroll)
        setAccessibilityLabel("Attachments")
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layout() {
        super.layout()
        scroll.frame = bounds.insetBy(dx:18,dy:0)
        row.frame.size = NSSize(width:max(scroll.bounds.width,CGFloat(files.count) * 174 - 8),height:Self.height)
    }
    override func draw(_ dirtyRect: NSRect) {
        NSColor.separatorColor.setFill(); NSRect(x:18,y:bounds.height - 1,width:max(0,bounds.width - 36),height:1).fill()
    }
    func update(_ values: [AttachedFile]) {
        guard files != values else { return }
        files = values; buttons.forEach { $0.removeFromSuperview() }; buttons = []
        for (index,file) in files.enumerated() {
            let button = NSButton(title:file.name,target:self,action:#selector(preview(_:)))
            button.frame = NSRect(x:CGFloat(index) * 174,y:2,width:166,height:30)
            button.bezelStyle = .rounded; button.imagePosition = .imageLeading; button.imageScaling = .scaleProportionallyDown
            button.font = .systemFont(ofSize:12); button.cell?.lineBreakMode = .byTruncatingMiddle
            button.tag = index; button.isEnabled = file.available
            button.identifier = NSUserInterfaceItemIdentifier(file.id)
            let type = UTType(mimeType:file.mime) ?? UTType(filenameExtension:(file.name as NSString).pathExtension) ?? .data
            let icon = NSWorkspace.shared.icon(for:type); icon.size = NSSize(width:20,height:20); button.image = icon
            button.toolTip = file.name + (file.available ? " — Preview" : " — Unavailable offline")
            button.setAccessibilityLabel(file.name); button.setAccessibilityHelp(file.available ? "Preview attachment. Use the context menu to download or remove it." : "This attachment will download when connected.")
            let menu = NSMenu()
            let preview = NSMenuItem(title:"Quick Look",action:#selector(previewItem(_:)),keyEquivalent:""); preview.target = self; preview.tag = index; preview.isEnabled = file.available
            preview.representedObject = file.id
            menu.addItem(preview)
            let download = NSMenuItem(title:"Download Attachment…",action:#selector(downloadItem(_:)),keyEquivalent:""); download.target = self; download.tag = index; download.isEnabled = file.available
            download.representedObject = file.id
            menu.addItem(download); menu.addItem(.separator())
            let remove = NSMenuItem(title:"Remove Attachment",action:#selector(removeItem(_:)),keyEquivalent:""); remove.target = self; remove.tag = index; menu.addItem(remove)
            remove.representedObject = file.id
            menu.autoenablesItems = false; button.menu = menu
            row.addSubview(button); buttons.append(button)
        }
        isHidden = files.isEmpty; needsLayout = true
        scroll.contentView.scroll(to:.zero)
    }
    @objc private func preview(_ sender: NSButton) {
        guard let index = files.firstIndex(where:{ $0.id == sender.identifier?.rawValue }) else { return }
        onPreview?(files[index],sender)
    }
    @objc private func previewItem(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String, let index = files.firstIndex(where:{ $0.id == id }) else { return }
        onPreview?(files[index],buttons[index])
    }
    @objc private func downloadItem(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String, let file = files.first(where:{ $0.id == id }) else { return }
        onDownload?(file)
    }
    @objc private func removeItem(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String, let file = files.first(where:{ $0.id == id }) else { return }
        onRemove?(file)
    }
}
