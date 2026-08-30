import Foundation
import WebKit

final class RiftBrowserKernelSync {
    weak var webView: WKWebView?

    private weak var browserStore: RiftBrowserStore?
    private var observer: NSObjectProtocol?

    init(browserStore: RiftBrowserStore) {
        self.browserStore = browserStore
        observer = NotificationCenter.default.addObserver(
            forName: .riftBrowserStateDidChange,
            object: browserStore,
            queue: .main
        ) { [weak self] _ in
            self?.push()
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    func push() {
        guard let webView, let browserStore else { return }
        let snapshot = browserStore.snapshot()
        guard JSONSerialization.isValidJSONObject(snapshot),
              let data = try? JSONSerialization.data(withJSONObject: snapshot, options: [])
        else { return }
        let encoded = data.base64EncodedString()
        let script = "window.RiftBrowser?.syncNativeState(JSON.parse(atob('\(encoded)')));"
        webView.evaluateJavaScript(script, completionHandler: nil)
    }
}
