import Foundation
import WebKit
import UIKit
import UniformTypeIdentifiers
import UserNotifications

final class RiftNativeBridge: NSObject, WKScriptMessageHandler, UIDocumentPickerDelegate {
    weak var webView: WKWebView?
    private var pendingPickerID: String?
    private var pendingPickerKind: String?
    private var mounts: [String: URL] = [:]

    func attach(to configuration: WKWebViewConfiguration) {
        configuration.userContentController.add(self, name: "riftNative")
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
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
                "share": true,
                "clipboard": true,
                "notifications": true,
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
        case "fs.list":
            listMount(id: id, args: args)
        case "fs.readText":
            readText(id: id, args: args)
        case "fs.writeText":
            writeText(id: id, args: args)
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

    private func topViewController() -> UIViewController? {
        let root = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .rootViewController
        var current = root
        while let presented = current?.presentedViewController { current = presented }
        return current
    }

    private func pickDirectory(id: String) {
        pendingPickerID = id
        pendingPickerKind = "directory"
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        DispatchQueue.main.async { [weak self] in self?.topViewController()?.present(picker, animated: true) }
    }

    private func pickDocument(id: String) {
        pendingPickerID = id
        pendingPickerKind = "document"
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        DispatchQueue.main.async { [weak self] in self?.topViewController()?.present(picker, animated: true) }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        if let id = pendingPickerID { respond(id: id, ok: false, error: "Picker cancelled") }
        pendingPickerID = nil
        pendingPickerKind = nil
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let id = pendingPickerID, let url = urls.first else { return }
        let kind = pendingPickerKind ?? "document"
        _ = url.startAccessingSecurityScopedResource()
        if kind == "directory" {
            let mountID = UUID().uuidString
            mounts[mountID] = url
            respond(id: id, value: ["mountId": mountID, "name": url.lastPathComponent, "kind": "directory"])
        } else {
            do {
                let text = try String(contentsOf: url, encoding: .utf8)
                respond(id: id, value: ["name": url.lastPathComponent, "kind": "document", "text": text])
            } catch {
                respond(id: id, ok: false, error: error.localizedDescription)
            }
        }
        pendingPickerID = nil
        pendingPickerKind = nil
    }

    private func mountedURL(_ args: [String: Any]) throws -> URL {
        guard let mountID = args["mountId"] as? String, let root = mounts[mountID] else {
            throw NSError(domain: "RiftNative", code: 1, userInfo: [NSLocalizedDescriptionKey: "Unknown mount"])
        }
        let relative = (args["path"] as? String ?? "")
            .replacingOccurrences(of: "\\", with: "/")
            .split(separator: "/")
            .filter { $0 != "." && $0 != ".." }
            .map(String.init)
        var url = root
        for part in relative { url.appendPathComponent(part) }
        return url
    }

    private func listMount(id: String, args: [String: Any]) {
        do {
            let url = try mountedURL(args)
            let values = try FileManager.default.contentsOfDirectory(
                at: url,
                includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey],
                options: [.skipsHiddenFiles]
            ).map { item -> [String: Any] in
                let resource = try? item.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
                return [
                    "name": item.lastPathComponent,
                    "kind": resource?.isDirectory == true ? "directory" : "file",
                    "size": resource?.fileSize ?? 0
                ]
            }
            respond(id: id, value: values)
        } catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func readText(id: String, args: [String: Any]) {
        do {
            let url = try mountedURL(args)
            respond(id: id, value: try String(contentsOf: url, encoding: .utf8))
        } catch { respond(id: id, ok: false, error: error.localizedDescription) }
    }

    private func writeText(id: String, args: [String: Any]) {
        do {
            let url = try mountedURL(args)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try (args["text"] as? String ?? "").write(to: url, atomically: true, encoding: .utf8)
            respond(id: id, value: true)
        } catch { respond(id: id, ok: false, error: error.localizedDescription) }
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
            if let error { self?.respond(id: id, ok: false, error: error.localizedDescription) }
            else { self?.respond(id: id, value: granted) }
        }
    }

    private func scheduleNotification(id: String, args: [String: Any]) {
        let content = UNMutableNotificationContent()
        content.title = args["title"] as? String ?? "RiftOS"
        content.body = args["body"] as? String ?? ""
        let seconds = max(1, args["seconds"] as? Double ?? 1)
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false))
        UNUserNotificationCenter.current().add(request) { [weak self] error in
            if let error { self?.respond(id: id, ok: false, error: error.localizedDescription) }
            else { self?.respond(id: id, value: true) }
        }
    }

    private func respond(id: String, ok: Bool = true, value: Any? = nil, error: String? = nil) {
        let payload: [Any] = [id, ok, value ?? NSNull(), error ?? NSNull()]
        guard let data = try? JSONSerialization.data(withJSONObject: payload), let json = String(data: data, encoding: .utf8) else { return }
        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript("window.RiftNative && window.RiftNative.__resolve.apply(null, \(json));")
        }
    }
}
