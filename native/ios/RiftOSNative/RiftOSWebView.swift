import SwiftUI
import WebKit

struct RiftOSWebView: UIViewRepresentable {
    final class Coordinator {
        let bridge = RiftNativeBridge()
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        context.coordinator.bridge.attach(to: configuration)

        let webView = WKWebView(frame: .zero, configuration: configuration)
        context.coordinator.bridge.webView = webView
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
