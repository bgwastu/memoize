import AppKit
import SwiftUI
import ServiceManagement
import KeyboardShortcuts
import MemoizeCore

extension KeyboardShortcuts.Name {
    static let toggleNote = Self("toggleNote",initial:.init(.x,modifiers:[.option,.command]))
}

@MainActor final class SettingsModel: ObservableObject {
    @Published var server = UserDefaults.standard.string(forKey:"server") ?? ""
    @Published var token = ""
    @Published var status = ""
    @Published var busy = false
    @Published var account = UserDefaults.standard.string(forKey:"user") ?? ""
    @Published var savedServer = UserDefaults.standard.string(forKey:"server") ?? ""
    @Published var connectionError: String?
    @Published var loginError: String?
    @Published var appearance = AppAppearance.saved {
        didSet { UserDefaults.standard.set(appearance.rawValue,forKey:"appearance"); appearance.apply() }
    }
    @Published var dock = UserDefaults.standard.object(forKey:"showDock") as? Bool ?? true {
        didSet { UserDefaults.standard.set(dock,forKey:"showDock"); NSApp.setActivationPolicy(dock ? .regular : .accessory) }
    }
    @Published var menuBar = UserDefaults.standard.object(forKey:"showMenuBar") as? Bool ?? true {
        didSet { UserDefaults.standard.set(menuBar,forKey:"showMenuBar"); onMenuBarVisibility?(menuBar) }
    }
    @Published var login = SMAppService.mainApp.status == .enabled
    var connect: ((String,String) async throws -> Void)?
    var onMenuBarVisibility: ((Bool) -> Void)?
    var hasSavedToken: Bool {
        guard !account.isEmpty, let entered = try? MemosClient.normalizedServer(server), let saved = try? MemosClient.normalizedServer(savedServer) else { return false }
        return entered == saved
    }
    var canConnect: Bool { !busy && (try? MemosClient.normalizedServer(server)) != nil && (hasSavedToken || !token.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty) }
    func save() {
        guard canConnect, let connect else { return }
        let enteredServer = server; let enteredToken = token
        busy = true; connectionError = nil; status = "Connecting…"
        Task {
            do {
                try await connect(enteredServer,enteredToken)
                server = UserDefaults.standard.string(forKey:"server") ?? enteredServer
                savedServer = server; account = UserDefaults.standard.string(forKey:"user") ?? ""
                token = ""; status = "Connected"
            } catch { connectionError = error.localizedDescription; status = "Connection failed" }
            busy = false
        }
    }
    func setLogin(_ enabled: Bool) {
        loginError = nil
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            login = SMAppService.mainApp.status == .enabled
            if SMAppService.mainApp.status == .requiresApproval {
                loginError = "Allow Memoize in System Settings → General → Login Items."
                SMAppService.openSystemSettingsLoginItems()
            }
        } catch { login = SMAppService.mainApp.status == .enabled; loginError = error.localizedDescription }
    }
}

private struct GeneralSettings: View {
    @ObservedObject var model: SettingsModel
    var body: some View {
        Form {
            Section("Appearance") {
                Picker("Theme",selection:$model.appearance) {
                    ForEach(AppAppearance.allCases,id:\.self) { Text($0.title).tag($0) }
                }.pickerStyle(.segmented)
                Toggle("Show in Dock",isOn:$model.dock)
                Toggle("Show in menu bar",isOn:$model.menuBar)
            }
            Section("Startup") {
                Toggle("Launch at login",isOn:Binding(get:{ model.login },set:{ model.setLogin($0) }))
                if let error = model.loginError { Text(error).font(.callout).foregroundStyle(.red).textSelection(.enabled) }
            }
            Section {
                LabeledContent("Memoize",value:Bundle.main.object(forInfoDictionaryKey:"CFBundleShortVersionString") as? String ?? "")
            }
        }.formStyle(.grouped).padding(12).frame(width:540,height:370)
    }
}

private struct MemosSettings: View {
    @ObservedObject var model: SettingsModel
    var body: some View {
        Form {
            Section {
                TextField("Server",text:$model.server,prompt:Text("https://memos.example.com"))
                    .textContentType(.URL).autocorrectionDisabled().disabled(model.busy)
                SecureField("Access token",text:$model.token,prompt:Text(model.hasSavedToken ? "Saved in Keychain" : "Personal access token"))
                    .disabled(model.busy)
            } header: { Text("Connection") }
            Section {
                if model.hasSavedToken {
                    LabeledContent("Account",value:model.account.replacingOccurrences(of:"users/",with:""))
                }
                HStack {
                    if model.busy { ProgressView().controlSize(.small); Text("Connecting…").foregroundStyle(.secondary) }
                    else if !model.status.isEmpty { Label(model.status,systemImage:model.connectionError == nil ? "checkmark.circle" : "exclamationmark.circle").foregroundStyle(model.connectionError == nil ? Color.secondary : Color.red) }
                    else if model.hasSavedToken { Label("Configured",systemImage:"checkmark.circle").foregroundStyle(.secondary) }
                    Spacer()
                    Button(model.hasSavedToken ? "Reconnect" : "Connect",action:model.save)
                        .disabled(!model.canConnect).keyboardShortcut(.defaultAction)
                }
                if let error = model.connectionError { Text(error).font(.callout).foregroundStyle(.red).textSelection(.enabled).fixedSize(horizontal:false,vertical:true) }
            }
        }.formStyle(.grouped).padding(12).frame(width:540,height:330)
    }
}

private struct ShortcutSettings: View {
    var body: some View {
        Form {
            Section("Global shortcut") {
                KeyboardShortcuts.Recorder("Show or hide Memoize",name:.toggleNote)
                HStack { Spacer(); Button("Restore Default") { KeyboardShortcuts.reset(.toggleNote) }.controlSize(.small) }
            }
            Section("While writing") {
                shortcut("New note","⌘N")
                shortcut("Older / newer note","⌘[ / ⌘]")
                shortcut("Find in note","⌘F")
                shortcut("Search all notes","⇧⌘F")
                shortcut("Toggle notes list","⌘0")
            }
        }.formStyle(.grouped).padding(12).frame(width:540,height:410)
    }
    private func shortcut(_ title: String, _ keys: String) -> some View {
        LabeledContent(title) { Text(keys).monospaced().foregroundStyle(.secondary) }
    }
}

@MainActor private final class SettingsController: NSTabViewController {
    init(model: SettingsModel) {
        super.init(nibName:nil,bundle:nil)
        let saved = UserDefaults.standard.string(forKey:"settingsPane")
        tabStyle = .toolbar
        let panes: [(String,String,CGFloat,NSViewController)] = [
            ("General","gearshape",370,NSHostingController(rootView:GeneralSettings(model:model))),
            ("Memos","server.rack",330,NSHostingController(rootView:MemosSettings(model:model))),
            ("Shortcuts","keyboard",410,NSHostingController(rootView:ShortcutSettings()))
        ]
        for (title,symbol,height,controller) in panes {
            controller.title = title
            controller.preferredContentSize = NSSize(width:540,height:height)
            let item = NSTabViewItem(viewController:controller); item.identifier = title; item.label = title
            item.image = NSImage(systemSymbolName:symbol,accessibilityDescription:title)
            addTabViewItem(item)
        }
        selectedTabViewItemIndex = panes.firstIndex(where:{ $0.0 == saved }) ?? (model.account.isEmpty ? 1 : 0)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func tabView(_ tabView: NSTabView, didSelect tabViewItem: NSTabViewItem?) {
        super.tabView(tabView,didSelect:tabViewItem)
        guard let item = tabViewItem else { return }
        UserDefaults.standard.set(item.label,forKey:"settingsPane")
        if let size = item.viewController?.preferredContentSize { view.window?.setContentSize(size) }
    }
}

@MainActor final class SettingsWindow {
    let model = SettingsModel()
    var pinned = true { didSet { loadedWindow?.level = pinned ? .floating : .normal } }
    private(set) var loadedWindow: NSWindow?
    private lazy var window: NSWindow = {
        let controller = SettingsController(model:model)
        let window = NSWindow(contentViewController:controller)
        window.styleMask = [.titled,.closable]; window.toolbarStyle = .preference
        window.setContentSize(controller.tabViewItems[controller.selectedTabViewItemIndex].viewController!.preferredContentSize)
        window.toolbar?.displayMode = .iconAndLabel
        window.toolbar?.allowsUserCustomization = false; window.toolbar?.autosavesConfiguration = false
        window.isReleasedWhenClosed = false; window.level = pinned ? .floating : .normal
        window.center(); loadedWindow = window; return window
    }()
    func show() { NSApp.unhide(nil); NSApp.activate(); window.makeKeyAndOrderFront(nil) }
    func selectPane(_ index: Int) {
        show(); (window.contentViewController as? NSTabViewController)?.selectedTabViewItemIndex = index
    }
}
