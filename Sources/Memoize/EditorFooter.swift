import AppKit

/// The hover target keeps its size when the controls disappear.
final class HoverToolbar: NSView {
    let glass = NSGlassEffectView()
    private var tracking: NSTrackingArea?
    private var hovered = false
    var hasToast = false { didSet { updateVisibility() } }
    var isRevealed: Bool { !glass.isHidden }

    override init(frame: NSRect) {
        super.init(frame:frame)
        glass.cornerRadius = 0; glass.style = .regular; glass.translatesAutoresizingMaskIntoConstraints = false
        addSubview(glass)
        NSLayoutConstraint.activate([glass.leadingAnchor.constraint(equalTo:leadingAnchor),glass.trailingAnchor.constraint(equalTo:trailingAnchor),glass.topAnchor.constraint(equalTo:topAnchor),glass.bottomAnchor.constraint(equalTo:bottomAnchor)])
        updateVisibility()
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override var isOpaque: Bool { true }
    override func draw(_ dirtyRect: NSRect) { NSColor.textBackgroundColor.setFill(); bounds.intersection(dirtyRect).fill() }
    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let tracking { removeTrackingArea(tracking) }
        let area = NSTrackingArea(rect:.zero,options:[.mouseEnteredAndExited,.activeAlways,.inVisibleRect],owner:self,userInfo:nil)
        addTrackingArea(area); tracking = area
        hovered = window.map { !$0.isMiniaturized && $0.isVisible && bounds.contains(convert($0.mouseLocationOutsideOfEventStream,from:nil)) } ?? false
        updateVisibility()
    }
    override func mouseEntered(with event: NSEvent) { hovered = true; updateVisibility() }
    override func mouseExited(with event: NSEvent) { hovered = false; updateVisibility() }
    func refreshHover() { updateTrackingAreas() }
    private func updateVisibility() { glass.isHidden = !(hovered || hasToast) }
}

final class SyncIndicator: NSImageView {
    enum State { case local, pending, working, synced, offline, failed, conflict }
    private(set) var state: State = .local

    override init(frame: NSRect) {
        super.init(frame:frame)
        imageScaling = .scaleNone
        set(.local,detail:"Saved on this Mac")
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    func set(_ state: State, detail: String) {
        if self.state != state || image == nil {
            self.state = state
            let symbol: String
            switch state {
            case .local: symbol = "internaldrive"
            case .pending: symbol = "clock.arrow.circlepath"
            case .working: symbol = "arrow.triangle.2.circlepath"
            case .synced: symbol = "checkmark.icloud"
            case .offline: symbol = "wifi.slash"
            case .failed: symbol = "exclamationmark.triangle"
            case .conflict: symbol = "doc.on.doc"
            }
            image = NSImage(systemSymbolName:symbol,accessibilityDescription:detail)?.withSymbolConfiguration(.init(pointSize:13,weight:.regular))
            contentTintColor = state == .failed ? .systemOrange : .secondaryLabelColor
        }
        let opacity: CGFloat = NSWorkspace.shared.accessibilityDisplayShouldIncreaseContrast || state == .failed ? 1 : 0.65
        if alphaValue != opacity { alphaValue = opacity }
        if toolTip != detail {
            toolTip = detail; setAccessibilityLabel(detail)
        }
    }
}

final class EditorToast: NSView {
    let glass = NSGlassEffectView()
    let message = NSTextField(labelWithString:"")
    let undoButton = NSButton(title:"Undo",target:nil,action:nil)
    var onUndo: (() -> Void)?
    private var generation = 0

    override init(frame: NSRect) {
        super.init(frame:frame)
        isHidden = true
        glass.cornerRadius = 16; glass.style = .regular; glass.translatesAutoresizingMaskIntoConstraints = false
        addSubview(glass)
        NSLayoutConstraint.activate([glass.leadingAnchor.constraint(equalTo:leadingAnchor),glass.trailingAnchor.constraint(equalTo:trailingAnchor),glass.topAnchor.constraint(equalTo:topAnchor),glass.bottomAnchor.constraint(equalTo:bottomAnchor)])
        message.font = .systemFont(ofSize:12); message.lineBreakMode = .byTruncatingTail
        message.setContentCompressionResistancePriority(.defaultLow,for:.horizontal)
        undoButton.bezelStyle = .inline; undoButton.isBordered = false
        undoButton.font = .systemFont(ofSize:12,weight:.medium); undoButton.contentTintColor = .controlAccentColor
        undoButton.target = self; undoButton.action = #selector(undo)
        undoButton.setAccessibilityHelp("Undo the action described in this message")
        undoButton.setContentCompressionResistancePriority(.required,for:.horizontal)
        let row = NSStackView(views:[message,undoButton]); row.orientation = .horizontal; row.spacing = 12
        row.edgeInsets = NSEdgeInsets(top:4,left:12,bottom:4,right:12)
        glass.contentView = row
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override var intrinsicContentSize: NSSize {
        NSSize(width:message.intrinsicContentSize.width + (undoButton.isHidden ? 0 : undoButton.intrinsicContentSize.width + 12) + 24,height:32)
    }
    func show(_ text: String?, canUndo: Bool = false) {
        generation += 1; let version = generation
        let reduceMotion = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        if let text {
            let wasHidden = isHidden
            message.stringValue = text; message.toolTip = text
            undoButton.isHidden = !canUndo; undoButton.isEnabled = true
            invalidateIntrinsicContentSize()
            isHidden = false
            if wasHidden { alphaValue = reduceMotion ? 1 : 0 }
            NSAnimationContext.runAnimationGroup { context in
                context.duration = reduceMotion ? 0 : 0.14; animator().alphaValue = 1
            }
            if window?.isVisible == true {
                NSAccessibility.post(element:self,notification:.announcementRequested,userInfo:[.announcement:text + (canUndo ? ". Undo available." : ""),.priority:NSAccessibilityPriorityLevel.medium.rawValue])
            }
        } else {
            guard !isHidden else { return }
            undoButton.isEnabled = false
            NSAnimationContext.runAnimationGroup { context in
                context.duration = reduceMotion ? 0 : 0.14; animator().alphaValue = 0
            } completionHandler: { [weak self] in
                Task { @MainActor in
                    guard let self, self.generation == version else { return }; self.isHidden = true
                }
            }
        }
    }
    @objc private func undo() { guard undoButton.isEnabled else { return }; undoButton.isEnabled = false; onUndo?() }
}
