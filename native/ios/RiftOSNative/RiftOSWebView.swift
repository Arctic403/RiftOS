import SwiftUI
import WebKit
import UIKit

struct RiftOSWebView: UIViewRepresentable {
    final class Coordinator: NSObject, WKNavigationDelegate {
        let bridge = RiftNativeBridge()

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard
                navigationAction.targetFrame?.isMainFrame == true,
                let url = navigationAction.request.url,
                ["http", "https"].contains(url.scheme?.lowercased() ?? "")
            else {
                decisionHandler(.allow)
                return
            }

            if url.host?.lowercased() == "arctic403.github.io" {
                decisionHandler(.allow)
                return
            }

            UIApplication.shared.open(url)
            decisionHandler(.cancel)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        context.coordinator.bridge.attach(to: configuration)

        let webView = WKWebView(frame: .zero, configuration: configuration)
        context.coordinator.bridge.webView = webView
        webView.navigationDelegate = context.coordinator
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsBackForwardNavigationGestures = false
        if #available(iOS 16.4, *) { webView.isInspectable = true }

        if let local = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "Web") {
            let root = local.deletingLastPathComponent()
            webView.loadFileURL(local, allowingReadAccessTo: root)
        } else if let remote = URL(string: "https://arctic403.github.io/RiftOS/") {
            webView.load(URLRequest(url: remote, cachePolicy: .reloadRevalidatingCacheData))
        }

        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}
}
