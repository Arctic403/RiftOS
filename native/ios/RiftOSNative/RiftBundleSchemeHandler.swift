import Foundation
import WebKit
import UniformTypeIdentifiers

final class RiftBundleSchemeHandler: NSObject, WKURLSchemeHandler {
    private let scheme = "riftos"

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let requestURL = urlSchemeTask.request.url, requestURL.scheme?.lowercased() == scheme else {
            fail(urlSchemeTask, code: 400, message: "Invalid RiftOS resource request")
            return
        }

        do {
            let fileURL = try bundledFileURL(for: requestURL)
            let data = try Data(contentsOf: fileURL, options: [.mappedIfSafe])
            let response = URLResponse(
                url: requestURL,
                mimeType: mimeType(for: fileURL),
                expectedContentLength: data.count,
                textEncodingName: isText(fileURL) ? "utf-8" : nil
            )
            urlSchemeTask.didReceive(response)
            urlSchemeTask.didReceive(data)
            urlSchemeTask.didFinish()
        } catch {
            fail(urlSchemeTask, code: 404, message: error.localizedDescription)
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}

    private func bundledFileURL(for requestURL: URL) throws -> URL {
        guard let resourceRoot = Bundle.main.resourceURL?.appendingPathComponent("Web", isDirectory: true) else {
            throw NSError(domain: "RiftBundle", code: 1, userInfo: [NSLocalizedDescriptionKey: "Bundled RiftOS Web directory is missing"])
        }

        var path = requestURL.path.removingPercentEncoding ?? requestURL.path
        if path.isEmpty || path == "/" { path = "/index.html" }
        if path.hasSuffix("/") { path += "index.html" }

        let pieces = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard !pieces.isEmpty, !pieces.contains("."), !pieces.contains("..") else {
            throw NSError(domain: "RiftBundle", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid bundled resource path"])
        }

        let root = resourceRoot.standardizedFileURL
        let candidate = pieces.reduce(root) { $0.appendingPathComponent($1) }.standardizedFileURL
        guard candidate.path.hasPrefix(root.path + "/"), FileManager.default.fileExists(atPath: candidate.path) else {
            throw NSError(domain: "RiftBundle", code: 3, userInfo: [NSLocalizedDescriptionKey: "Bundled RiftOS resource not found: \(path)"])
        }
        return candidate
    }

    private func mimeType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "html", "htm": return "text/html"
        case "js", "mjs": return "text/javascript"
        case "css": return "text/css"
        case "json": return "application/json"
        case "webmanifest": return "application/manifest+json"
        case "svg": return "image/svg+xml"
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "ico": return "image/x-icon"
        case "txt", "md": return "text/plain"
        case "wasm": return "application/wasm"
        default:
            if let type = UTType(filenameExtension: url.pathExtension), let mime = type.preferredMIMEType { return mime }
            return "application/octet-stream"
        }
    }

    private func isText(_ url: URL) -> Bool {
        ["html", "htm", "js", "mjs", "css", "json", "webmanifest", "svg", "txt", "md"].contains(url.pathExtension.lowercased())
    }

    private func fail(_ task: WKURLSchemeTask, code: Int, message: String) {
        task.didFailWithError(NSError(domain: "RiftBundle", code: code, userInfo: [NSLocalizedDescriptionKey: message]))
    }
}
