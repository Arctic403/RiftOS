import SwiftUI
import WebKit
import UIKit

struct RiftOSWebView: UIViewRepresentable {
    @ObservedObject var browserStore: RiftBrowserStore

    final class Coordinator: NSObject, WKNavigationDelegate {
        let bridge: RiftNativeBridge
        let browserBridge: RiftBrowserCommandBridge
        let schemeHandler = RiftBundleSchemeHandler()
        let browserSync: RiftBrowserKernelSync

        init(browserStore: RiftBrowserStore) {
            bridge = RiftNativeBridge(browserStore: browserStore)
            browserBridge = RiftBrowserCommandBridge(browserStore: browserStore)
            browserSync = RiftBrowserKernelSync(browserStore: browserStore)
            super.init()
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard navigationAction.targetFrame?.isMainFrame == true, let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }

            let scheme = url.scheme?.lowercased() ?? ""
            if scheme == "riftos" {
                decisionHandler(.allow)
                return
            }

            // The privileged shell is local-only. Every real web destination is
            // handed to RiftBrowser, whose WKWebViews do not contain RiftNative.
            if ["http", "https"].contains(scheme) {
                bridge.openBrowser(url.absoluteString)
                decisionHandler(.cancel)
                return
            }

            if UIApplication.shared.canOpenURL(url) { UIApplication.shared.open(url) }
            decisionHandler(.cancel)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            browserSync.push()
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(browserStore: browserStore) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.setURLSchemeHandler(context.coordinator.schemeHandler, forURLScheme: "riftos")
        configuration.userContentController.add(context.coordinator.browserBridge, name: "riftBrowser")
        context.coordinator.bridge.attach(to: configuration)

        let webView = WKWebView(frame: .zero, configuration: configuration)
        context.coordinator.bridge.webView = webView
        context.coordinator.browserSync.webView = webView
        webView.navigationDelegate = context.coordinator
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsBackForwardNavigationGestures = false
        if #available(iOS 16.4, *) { webView.isInspectable = true }

        // Native RiftOS never boots from GitHub Pages. The shell is copied into
        // the app bundle at build time and served through the local riftos://
        // scheme so module scripts and relative assets share one stable origin.
        if let local = URL(string: "riftos:///index.html") {
            webView.load(URLRequest(url: local, cachePolicy: .reloadIgnoringLocalCacheData))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.browserSync.webView = webView
    }
}
