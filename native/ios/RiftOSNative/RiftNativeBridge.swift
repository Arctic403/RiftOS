import Foundation
import WebKit
import UIKit
import UniformTypeIdentifiers
import UserNotifications

final class RiftNativeBridge: NSObject, WKScriptMessageHandler, UIDocumentPickerDelegate {
    weak var webView: WKWebView?

    private let browserStore: RiftBrowserStore
    private let workspace = RiftWorkspace.shared
    private lazy var patchEngine = RiftPatchEngine(workspace: workspace)

    init(browserStore: RiftBrowserStore) {
        self.browserStore = browserStore
        super.init()
    }

    func openBrowser(_ url: String = "https://chatgpt.com") {
        DispatchQueue.main.async { [weak self] in
            self?.browserStore.open(url)
        }
    }

    private struct MountRecord: Codable {
        let id: String
        let name: String
        let bookmark: Data
    }

    private let bookmarkKey = "RiftOS.NativeMounts.v1"
    private let workspaceMountID = "rift-workspace"
    private var pendingPickerID: String?
    private var pendingPickerKind: String?
    private var mounts: [String: URL] = [:]
    private var mountNames: [String: String] = [:]

    func attach(to configuration: WKWebViewConfiguration) {
        configuration.userContentController.add(self, name: "riftNative")
        restoreMounts()
    }

    deinit {
        for url in mounts.values {
            url.stopAccessingSecurityScopedResource()
        }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        // Only RiftOS's top-level document receives native capabilities.
        // Sandboxed RiftApps and embedded web content must go through the
        // capability broker in the main RiftOS document.
        guard message.frameInfo.isMainFrame, isTrustedRiftOSOrigin(message.frameInfo.securityOrigin) else { return }

        guard
            let body = message.body as? [String: Any],
            let id = body["id"] as? String,
            let method = body["method"] as? String
        else { return }

        let args = body["args"] as? [String: Any] ?? [:]

        switch method {
        case "native.capabilities":
            respond(id: id, value: [
                "files": true,
                "persistentFileMounts": true,
                "share": true,
                "clipboard": true,
                "notifications": true,
                "browser": true,
                "workspace": true,
                "jsonPatches": true,
                "patchRollback": true,
                "background": false
            ])
        case "device.info":
            let device = UIDevice.current
            respond(id: id, value: [
                "name": device.name,
                "model": device.model,
                "systemName": device.systemName,
                "systemVersion": device.systemVersion
            ])
        case "files.pickDirectory":
            pickDirectory(id: id)
        case "files.pickDocument":
            pickDocument(id: id)
        case "files.mounts":
            respond(id: id, value: mountList())
        case "files.unmount":
            unmount(id: id, args: args)
        case "browser.open":
            let url = args["url"] as? String
            let newTab = args["newTab"] as? Bool ?? false
            DispatchQueue.main.async { [weak self] in
                self?.browserStore.open(url, newTab: newTab)
                self?.respond(id: id, value: true)
            }
        case "browser.close":
            DispatchQueue.main.async { [weak self] in
                self?.browserStore.closeBrowser()
                self?.respond(id: id, value: true)
            }
        case "workspace.info":
            workspaceInfo(id: id)
        case "workspace.list":
            workspaceList(id: id, args: args)
        case "workspace.stat":
            workspaceStat(id: id, args: args)
        case "workspace.readText":
            workspaceReadText(id: id, args: args)
        case "workspace.writeText":
            workspaceWriteText(id: id, args: args)
        case "workspace.mkdir":
            workspaceMkdir(id: id, args: args)
        case "workspace.remove":
            workspaceRemove(id: id, args: args)
        case "workspace.move":
            workspaceMove(id: id, args: args)
        case "workspace.previewPatch":
            workspacePatch(id: id, args: args, apply: false)
        case "workspace.applyPatch":
            workspacePatch(id: id, args: args, apply: true)
        case "workspace.history":
            workspaceHistory(id: id)
        case "workspace.rollback":
            workspaceRollback(id: id, args: args)
        case "workspace.copyFromMount":
            workspaceCopyFromMount(id: id, args: args)
        case "workspace.copyToMount":
            workspaceCopyToMount(id: id, args: args)
        case "fs.list":
            listMount(id: id, args: args)
        case "fs.stat":
            statMount(id: id, args: args)
        case "fs.readText":
            readText(id: id, args: args)
        case "fs.writeText":
            writeText(id: id, args: args)
        case "fs.mkdir":
            makeDirectory(id: id, args: args)
        case "fs.remove":
            removeItem(id: id, args: args)
        case "clipboard.readText":
            respond(id: id, value: UIPasteboard.general.string ?? "")
        case "clipboard.writeText":
            UIPasteboard.general.string = args["text"] as? String ?? ""
            respond(id: id, value: true)
        case "share.text":
            shareText(id: id, args: args)
        case "notifications.request":
            requestNotifications(id: id)
        case "notifications.schedule":
            scheduleNotification(id: id, args: args)
        default:
            respond(id: id, ok: false, error: "Unsupported native method: \(method)")
        }
    }

    private func isTrustedRiftOSOrigin(_ origin: WKSecurityOrigin) -> Bool {
        let host = origin.host.lowercased()
        // Bundled file:// RiftOS has an empty host. Production remote fallback is
        // pinned to the RiftOS GitHub Pages origin. Browser tabs never receive
        // this script-message handler at all.
        return host.isEmpty || host == "arctic403.github.io"
    }

    private func workspaceInfo(id: String) {
        do { respond(id: id, value: try workspace.info()) }
        catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceList(id: String, args: [String: Any]) {
        do {
            let rows = try workspace.list(
                path: args["path"] as? String ?? "",
                recursive: args["recursive"] as? Bool ?? true,
                includeHidden: args["includeHidden"] as? Bool ?? false
            )
            respond(id: id, value: rows)
        } catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceStat(id: String, args: [String: Any]) {
        do { respond(id: id, value: try workspace.stat(path: args["path"] as? String ?? "") ?? NSNull()) }
        catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceReadText(id: String, args: [String: Any]) {
        guard let path = args["path"] as? String else { respond(id: id, ok: false, error: "path is required"); return }
        do { respond(id: id, value: try workspace.readText(path: path)) }
        catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceWriteText(id: String, args: [String: Any]) {
        guard let path = args["path"] as? String else { respond(id: id, ok: false, error: "path is required"); return }
        do { respond(id: id, value: try workspace.writeText(path: path, text: args["text"] as? String ?? "")) }
        catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceMkdir(id: String, args: [String: Any]) {
        guard let path = args["path"] as? String else { respond(id: id, ok: false, error: "path is required"); return }
        do { try workspace.makeDirectory(path: path); respond(id: id, value: true) }
        catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceRemove(id: String, args: [String: Any]) {
        guard let path = args["path"] as? String else { respond(id: id, ok: false, error: "path is required"); return }
        do { try workspace.remove(path: path); respond(id: id, value: true) }
        catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceMove(id: String, args: [String: Any]) {
        guard let path = args["path"] as? String, let newPath = args["newPath"] as? String else {
            respond(id: id, ok: false, error: "path and newPath are required"); return
        }
        do { try workspace.move(from: path, to: newPath); respond(id: id, value: true) }
        catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func patchData(_ args: [String: Any]) throws -> Data {
        if let json = args["json"] as? String, let data = json.data(using: .utf8) { return data }
        if let patch = args["patch"] {
            guard JSONSerialization.isValidJSONObject(patch) else {
                throw NSError(domain: "RiftNative", code: 40, userInfo: [NSLocalizedDescriptionKey: "patch must be a JSON object or json string"])
            }
            return try JSONSerialization.data(withJSONObject: patch)
        }
        throw NSError(domain: "RiftNative", code: 41, userInfo: [NSLocalizedDescriptionKey: "patch or json is required"])
    }

    private func workspacePatch(id: String, args: [String: Any], apply: Bool) {
        do {
            let data = try patchData(args)
            respond(id: id, value: apply ? try patchEngine.apply(data: data) : try patchEngine.preview(data: data))
        } catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceHistory(id: String) {
        do { respond(id: id, value: try patchEngine.history()) }
        catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceRollback(id: String, args: [String: Any]) {
        do { respond(id: id, value: try patchEngine.rollback(id: args["historyId"] as? String)) }
        catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceCopyFromMount(id: String, args: [String: Any]) {
        guard let destination = args["destination"] as? String else {
            respond(id: id, ok: false, error: "destination is required"); return
        }
        do {
            let source = try mountedURL(args)
            respond(id: id, value: try workspace.copyItem(from: source, to: destination))
        } catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func workspaceCopyToMount(id: String, args: [String: Any]) {
        guard let sourcePath = args["source"] as? String, let mountID = args["mountId"] as? String else {
            respond(id: id, ok: false, error: "source and mountId are required"); return
        }
        guard mountID != workspaceMountID else {
            respond(id: id, ok: false, error: "Use workspace move/write APIs inside RiftWorkspace"); return
        }
        do {
            let destination = try mountedURL([
                "mountId": mountID,
                "path": args["path"] as? String ?? ""
            ])
            try workspace.copyItemToExternal(path: sourcePath, destination: destination)
            respond(id: id, value: true)
        } catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func topViewController() -> UIViewController? {
        let root = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .rootViewController

        var current = root
        while let presented = current?.presentedViewController {
            current = presented
        }
        return current
    }

    private func pickDirectory(id: String) {
        pendingPickerID = id
        pendingPickerKind = "directory"
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        DispatchQueue.main.async { [weak self] in
            self?.topViewController()?.present(picker, animated: true)
        }
    }

    private func pickDocument(id: String) {
        pendingPickerID = id
        pendingPickerKind = "document"
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        DispatchQueue.main.async { [weak self] in
            self?.topViewController()?.present(picker, animated: true)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        if let id = pendingPickerID {
            respond(id: id, ok: false, error: "Picker cancelled")
        }
        pendingPickerID = nil
        pendingPickerKind = nil
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let id = pendingPickerID, let url = urls.first else { return }
        let kind = pendingPickerKind ?? "document"

        guard url.startAccessingSecurityScopedResource() else {
            respond(id: id, ok: false, error: "Could not access the selected Files location")
            pendingPickerID = nil
            pendingPickerKind = nil
            return
        }

        if kind == "directory" {
            do {
                let mountID = UUID().uuidString
                let bookmark = try url.bookmarkData(
                    options: [.minimalBookmark],
                    includingResourceValuesForKeys: nil,
                    relativeTo: nil
                )
                mounts[mountID] = url
                mountNames[mountID] = url.lastPathComponent
                persistMount(id: mountID, name: url.lastPathComponent, bookmark: bookmark)
                respond(id: id, value: [
                    "mountId": mountID,
                    "name": url.lastPathComponent,
                    "kind": "directory",
                    "persistent": true
                ])
            } catch {
                url.stopAccessingSecurityScopedResource()
                respond(id: id, ok: false, error: error.localizedDescription)
            }
        } else {
            defer { url.stopAccessingSecurityScopedResource() }
            do {
                let text = try String(contentsOf: url, encoding: .utf8)
                respond(id: id, value: [
                    "name": url.lastPathComponent,
                    "kind": "document",
                    "text": text
                ])
            } catch {
                respond(id: id, ok: false, error: error.localizedDescription)
            }
        }

        pendingPickerID = nil
        pendingPickerKind = nil
    }

    private func loadRecords() -> [MountRecord] {
        guard
            let data = UserDefaults.standard.data(forKey: bookmarkKey),
            let records = try? JSONDecoder().decode([MountRecord].self, from: data)
        else { return [] }
        return records
    }

    private func saveRecords(_ records: [MountRecord]) {
        guard let data = try? JSONEncoder().encode(records) else { return }
        UserDefaults.standard.set(data, forKey: bookmarkKey)
    }

    private func persistMount(id: String, name: String, bookmark: Data) {
        var records = loadRecords().filter { $0.id != id }
        records.append(MountRecord(id: id, name: name, bookmark: bookmark))
        saveRecords(records)
    }

    private func restoreMounts() {
        var valid: [MountRecord] = []
        for record in loadRecords() {
            var stale = false
            do {
                let url = try URL(
                    resolvingBookmarkData: record.bookmark,
                    // `.withSecurityScope` is macOS-only. On iOS the document-picker
                    // bookmark is resolved normally, then access is activated below.
                    options: [],
                    relativeTo: nil,
                    bookmarkDataIsStale: &stale
                )
                guard url.startAccessingSecurityScopedResource() else { continue }
                mounts[record.id] = url
                mountNames[record.id] = record.name
                let bookmark = stale
                    ? try url.bookmarkData(options: [.minimalBookmark], includingResourceValuesForKeys: nil, relativeTo: nil)
                    : record.bookmark
                valid.append(MountRecord(id: record.id, name: record.name, bookmark: bookmark))
            } catch {
                continue
            }
        }
        saveRecords(valid)
    }

    private func mountList() -> [[String: Any]] {
        let workspaceMount: [String: Any] = [
            "mountId": workspaceMountID,
            "name": "RiftWorkspace",
            "kind": "directory",
            "persistent": true,
            "system": true
        ]
        let external = mounts.keys.sorted().compactMap { id -> [String: Any]? in
            guard mounts[id] != nil else { return nil }
            return [
                "mountId": id,
                "name": mountNames[id] ?? "Files",
                "kind": "directory",
                "persistent": true
            ]
        }
        return [workspaceMount] + external
    }

    private func unmount(id: String, args: [String: Any]) {
        guard let mountID = args["mountId"] as? String else {
            respond(id: id, ok: false, error: "mountId is required")
            return
        }
        if mountID == workspaceMountID {
            respond(id: id, ok: false, error: "RiftWorkspace is a protected system mount")
            return
        }

        mounts[mountID]?.stopAccessingSecurityScopedResource()
        mounts.removeValue(forKey: mountID)
        mountNames.removeValue(forKey: mountID)
        saveRecords(loadRecords().filter { $0.id != mountID })
        respond(id: id, value: true)
    }

    private func mountedURL(_ args: [String: Any]) throws -> URL {
        guard let mountID = args["mountId"] as? String else {
            throw NSError(domain: "RiftNative", code: 1, userInfo: [NSLocalizedDescriptionKey: "mountId is required"])
        }

        if mountID == workspaceMountID {
            return try workspace.url(for: args["path"] as? String ?? "")
        }

        guard let root = mounts[mountID] else {
            throw NSError(domain: "RiftNative", code: 1, userInfo: [NSLocalizedDescriptionKey: "Unknown mount"])
        }

        let raw = (args["path"] as? String ?? "").replacingOccurrences(of: "\\", with: "/")
        guard !raw.hasPrefix("/"), !raw.contains("\0") else {
            throw NSError(domain: "RiftNative", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid mount path"])
        }
        let relative = raw.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard !relative.contains("."), !relative.contains("..") else {
            throw NSError(domain: "RiftNative", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid mount path"])
        }
        if relative.isEmpty { return root.standardizedFileURL }

        var candidate = root
        for part in relative { candidate.appendPathComponent(part) }
        candidate = candidate.standardizedFileURL

        let resolvedRoot = root.standardizedFileURL.resolvingSymlinksInPath()
        let resolvedParent = candidate.deletingLastPathComponent().resolvingSymlinksInPath()
        let parentPath = resolvedParent.path
        let rootPath = resolvedRoot.path
        guard parentPath == rootPath || parentPath.hasPrefix(rootPath + "/") else {
            throw NSError(domain: "RiftNative", code: 3, userInfo: [NSLocalizedDescriptionKey: "Mount path escapes selected directory"])
        }
        if FileManager.default.fileExists(atPath: candidate.path) {
            let resolvedCandidate = candidate.resolvingSymlinksInPath().path
            guard resolvedCandidate == rootPath || resolvedCandidate.hasPrefix(rootPath + "/") else {
                throw NSError(domain: "RiftNative", code: 3, userInfo: [NSLocalizedDescriptionKey: "Mount path escapes selected directory"])
            }
        }
        return candidate
    }

    private func isWorkspaceMount(_ args: [String: Any]) -> Bool {
        (args["mountId"] as? String) == workspaceMountID
    }

    private func requireWritableMountedPath(_ args: [String: Any]) throws {
        let path = args["path"] as? String ?? ""
        guard !path.trimmingCharacters(in: CharacterSet(charactersIn: "/ ")).isEmpty else {
            throw NSError(domain: "RiftNative", code: 4, userInfo: [NSLocalizedDescriptionKey: "Mount root cannot be modified directly"])
        }
        if isWorkspaceMount(args) { try workspace.assertEditable(path) }
    }

    private func descriptor(_ url: URL, root: URL) -> [String: Any] {
        let keys: Set<URLResourceKey> = [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
        let resource = try? url.resourceValues(forKeys: keys)
        let relative = url.path.replacingOccurrences(of: root.path, with: "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return [
            "path": relative,
            "name": url.lastPathComponent,
            "kind": resource?.isDirectory == true ? "directory" : "file",
            "size": resource?.fileSize ?? 0,
            "modified": Int((resource?.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000)
        ]
    }

    private func listMount(id: String, args: [String: Any]) {
        do {
            let root = try mountedURL(args)
            let recursive = args["recursive"] as? Bool ?? true
            var rows: [[String: Any]] = []

            if recursive {
                let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
                guard let enumerator = FileManager.default.enumerator(
                    at: root,
                    includingPropertiesForKeys: keys,
                    options: [.skipsHiddenFiles, .skipsPackageDescendants]
                ) else {
                    respond(id: id, value: rows)
                    return
                }

                for case let url as URL in enumerator {
                    rows.append(descriptor(url, root: root))
                }
            } else {
                let children = try FileManager.default.contentsOfDirectory(
                    at: root,
                    includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
                    options: [.skipsHiddenFiles]
                )
                rows = children.map { descriptor($0, root: root) }
            }

            respond(id: id, value: rows)
        } catch {
            respond(id: id, ok: false, error: error.localizedDescription)
        }
    }

    private func statMount(id: String, args: [String: Any]) {
        do {
            let url = try mountedURL(args)
            guard FileManager.default.fileExists(atPath: url.path) else {
                respond(id: id, value: NSNull())
                return
            }
            let rootID = args["mountId"] as? String ?? ""
            let root = mounts[rootID] ?? url
            respond(id: id, value: descriptor(url, root: root))
        } catch {
            respond(id: id, ok: false, error: error.localizedDescription)
        }
    }

    private func readText(id: String, args: [String: Any]) {
        do {
            let url = try mountedURL(args)
            respond(id: id, value: try String(contentsOf: url, encoding: .utf8))
        } catch {
            respond(id: id, ok: false, error: error.localizedDescription)
        }
    }

    private func writeText(id: String, args: [String: Any]) {
        do {
            try requireWritableMountedPath(args)
            let url = try mountedURL(args)
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true,
                attributes: nil
            )
            try (args["text"] as? String ?? "").write(to: url, atomically: true, encoding: .utf8)
            respond(id: id, value: true)
        } catch {
            respond(id: id, ok: false, error: error.localizedDescription)
        }
    }

    private func makeDirectory(id: String, args: [String: Any]) {
        do {
            try requireWritableMountedPath(args)
            let url = try mountedURL(args)
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: nil)
            respond(id: id, value: true)
        } catch {
            respond(id: id, ok: false, error: error.localizedDescription)
        }
    }

    private func removeItem(id: String, args: [String: Any]) {
        do {
            try requireWritableMountedPath(args)
            let url = try mountedURL(args)
            if FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
            }
            respond(id: id, value: true)
        } catch {
            respond(id: id, ok: false, error: error.localizedDescription)
        }
    }

    private func shareText(id: String, args: [String: Any]) {
        let text = args["text"] as? String ?? ""
        DispatchQueue.main.async { [weak self] in
            guard let self, let presenter = self.topViewController() else {
                self?.respond(id: id, ok: false, error: "No native presenter")
                return
            }
            let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            presenter.present(controller, animated: true)
            self.respond(id: id, value: true)
        }
    }

    private func requestNotifications(id: String) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { [weak self] granted, error in
            if let error {
                self?.respond(id: id, ok: false, error: error.localizedDescription)
            } else {
                self?.respond(id: id, value: granted)
            }
        }
    }

    private func scheduleNotification(id: String, args: [String: Any]) {
        let content = UNMutableNotificationContent()
        content.title = args["title"] as? String ?? "RiftOS"
        content.body = args["body"] as? String ?? ""
        let seconds = max(1, args["seconds"] as? Double ?? 1)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: trigger)

        UNUserNotificationCenter.current().add(request) { [weak self] error in
            if let error {
                self?.respond(id: id, ok: false, error: error.localizedDescription)
            } else {
                self?.respond(id: id, value: true)
            }
        }
    }

    private func respond(id: String, ok: Bool = true, value: Any? = nil, error: String? = nil) {
        let payload: [Any] = [id, ok, value ?? NSNull(), error ?? NSNull()]
        guard
            let data = try? JSONSerialization.data(withJSONObject: payload),
            let json = String(data: data, encoding: .utf8)
        else { return }

        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript(
                "window.RiftNative && window.RiftNative.__resolve.apply(null, \(json));"
            )
        }
    }
}
