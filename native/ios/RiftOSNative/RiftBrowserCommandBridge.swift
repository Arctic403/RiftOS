import Foundation
import WebKit

final class RiftBrowserCommandBridge: NSObject, WKScriptMessageHandler {
    private weak var browserStore: RiftBrowserStore?

    init(browserStore: RiftBrowserStore) {
        self.browserStore = browserStore
        super.init()
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        // This handler exists only on the trusted RiftOS shell WKWebView. Browser
        // tabs never receive it. Restrict commands to the top-level local shell.
        guard message.frameInfo.isMainFrame, message.frameInfo.securityOrigin.host.isEmpty else { return }
        guard let body = message.body as? [String: Any], let method = body["method"] as? String else { return }
        let args = body["args"] as? [String: Any] ?? [:]

        DispatchQueue.main.async { [weak self] in
            guard let store = self?.browserStore else { return }
            switch method {
            case "open":
                store.open(args["url"] as? String, newTab: args["newTab"] as? Bool ?? false)
            case "newTab":
                store.createTab(args["url"] as? String ?? "https://chatgpt.com")
            case "selectTab":
                if let raw = args["id"] as? String, let id = UUID(uuidString: raw) { store.select(id) }
            case "closeTab":
                if let raw = args["id"] as? String, let id = UUID(uuidString: raw) { store.close(id) }
                else if let id = store.selectedID { store.close(id) }
            case "navigate":
                guard let url = args["url"] as? String else { return }
                let tabID = (args["id"] as? String).flatMap(UUID.init(uuidString:))
                store.navigate(url, tabID: tabID)
            case "back": store.goBack()
            case "forward": store.goForward()
            case "reload": store.reload()
            case "stop": store.stop()
            case "desktop":
                let tabID = (args["id"] as? String).flatMap(UUID.init(uuidString:))
                store.setDesktopMode(args["enabled"] as? Bool ?? true, tabID: tabID)
            case "share": store.share()
            case "find": store.findOnPage()
            case "closeSurface": store.closeBrowser()
            default: break
            }
        }
    }
}
