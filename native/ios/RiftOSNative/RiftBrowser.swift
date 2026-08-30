import SwiftUI
import WebKit
import UIKit
import Combine

final class RiftBrowserTabSession: NSObject, ObservableObject, Identifiable, WKNavigationDelegate, WKUIDelegate {
    let id = UUID()

    @Published var title: String = "New Tab"
    @Published var addressText: String = ""
    @Published var isLoading = false
    @Published var estimatedProgress: Double = 0
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var prefersDesktopMode = true

    private var observations: [NSKeyValueObservation] = []

    lazy var webView: WKWebView = {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.defaultWebpagePreferences.preferredContentMode = .desktop
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true

        // RiftBrowser owns the chrome and tab model; Apple WebKit owns the web
        // engine. Never attach RiftNative to ordinary browser tabs.
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        view.uiDelegate = self
        view.allowsBackForwardNavigationGestures = true
        view.scrollView.keyboardDismissMode = .interactive
        if #available(iOS 16.4, *) { view.isInspectable = true }
        return view
    }()

    override init() {
        super.init()
        installObservers()
    }

    deinit {
        observations.forEach { $0.invalidate() }
    }

    func load(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard let url = destination(for: trimmed) else { return }
        applyContentMode()
        addressText = url.absoluteString
        webView.load(URLRequest(url: url, cachePolicy: .useProtocolCachePolicy, timeoutInterval: 60))
    }

    func setDesktopMode(_ enabled: Bool, reload: Bool = true) {
        prefersDesktopMode = enabled
        applyContentMode()
        if reload, webView.url != nil { webView.reload() }
    }

    func goBack() { if webView.canGoBack { webView.goBack() } }
    func goForward() { if webView.canGoForward { webView.goForward() } }
    func reload() { applyContentMode(); webView.reload() }
    func stop() { webView.stopLoading() }

    private func applyContentMode() {
        webView.configuration.defaultWebpagePreferences.preferredContentMode = prefersDesktopMode ? .desktop : .mobile
    }

    private func destination(for input: String) -> URL? {
        if let url = URL(string: input), let scheme = url.scheme?.lowercased(), ["http", "https", "about"].contains(scheme) {
            return url
        }
        if input.contains(".") && !input.contains(" ") {
            return URL(string: "https://\(input)")
        }
        var components = URLComponents(string: "https://www.google.com/search")
        components?.queryItems = [URLQueryItem(name: "q", value: input)]
        return components?.url
    }

    private func installObservers() {
        let view = webView
        observations = [
            view.observe(\.title, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async { self?.title = (view.title?.isEmpty == false ? view.title! : "New Tab") }
            },
            view.observe(\.url, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async { self?.addressText = view.url?.absoluteString ?? self?.addressText ?? "" }
            },
            view.observe(\.isLoading, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async { self?.isLoading = view.isLoading }
            },
            view.observe(\.estimatedProgress, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async { self?.estimatedProgress = view.estimatedProgress }
            },
            view.observe(\.canGoBack, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async { self?.canGoBack = view.canGoBack }
            },
            view.observe(\.canGoForward, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async { self?.canGoForward = view.canGoForward }
            }
        ]
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url, let scheme = url.scheme?.lowercased() else {
            decisionHandler(.allow)
            return
        }
        if ["http", "https", "about", "blob", "data"].contains(scheme) {
            applyContentMode()
            decisionHandler(.allow)
        } else {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
        }
    }

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            webView.load(URLRequest(url: url))
        }
        return nil
    }
}

final class RiftBrowserStore: ObservableObject {
    @Published private(set) var tabs: [RiftBrowserTabSession] = []
    @Published var selectedID: UUID?
    @Published var isPresented = false

    var selected: RiftBrowserTabSession? {
        if let selectedID, let tab = tabs.first(where: { $0.id == selectedID }) { return tab }
        return tabs.first
    }

    func open(_ url: String? = nil, newTab: Bool = false) {
        isPresented = true
        let destination = url?.trimmingCharacters(in: .whitespacesAndNewlines)
        if newTab || selected == nil {
            createTab(destination?.isEmpty == false ? destination! : "https://chatgpt.com")
        } else if let destination, !destination.isEmpty {
            selected?.load(destination)
        }
    }

    @discardableResult
    func createTab(_ url: String = "https://chatgpt.com") -> RiftBrowserTabSession {
        let tab = RiftBrowserTabSession()
        tabs.append(tab)
        selectedID = tab.id
        tab.load(url)
        return tab
    }

    func select(_ id: UUID) { selectedID = id }

    func close(_ id: UUID) {
        guard let index = tabs.firstIndex(where: { $0.id == id }) else { return }
        tabs[index].webView.stopLoading()
        tabs.remove(at: index)
        if tabs.isEmpty {
            selectedID = nil
            isPresented = false
        } else if selectedID == id {
            selectedID = tabs[min(index, tabs.count - 1)].id
        }
    }

    func closeBrowser() { isPresented = false }
}

struct RiftBrowserWebView: UIViewRepresentable {
    @ObservedObject var session: RiftBrowserTabSession

    func makeUIView(context: Context) -> WKWebView { session.webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct RiftBrowserView: View {
    @ObservedObject var store: RiftBrowserStore

    var body: some View {
        VStack(spacing: 0) {
            if let selected = store.selected {
                RiftBrowserChrome(store: store, session: selected)
                if selected.isLoading {
                    ProgressView(value: selected.estimatedProgress)
                        .progressViewStyle(.linear)
                }
                RiftBrowserWebView(session: selected)
                    .id(selected.id)
            } else {
                ContentUnavailableView("No Browser Tabs", systemImage: "globe")
            }
        }
        .background(Color(uiColor: .systemBackground))
    }
}

private struct RiftBrowserChrome: View {
    @ObservedObject var store: RiftBrowserStore
    @ObservedObject var session: RiftBrowserTabSession
    @FocusState private var addressFocused: Bool

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 10) {
                Button(action: store.closeBrowser) {
                    Image(systemName: "xmark")
                        .frame(width: 30, height: 30)
                }
                .accessibilityLabel("Close RiftBrowser")

                TextField("Search or enter website", text: $session.addressText)
                    .focused($addressFocused)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .submitLabel(.go)
                    .onSubmit {
                        session.load(session.addressText)
                        addressFocused = false
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))

                Button {
                    if session.isLoading { session.stop() } else { session.reload() }
                } label: {
                    Image(systemName: session.isLoading ? "xmark" : "arrow.clockwise")
                        .frame(width: 30, height: 30)
                }
            }

            HStack(spacing: 18) {
                Button(action: session.goBack) { Image(systemName: "chevron.left") }
                    .disabled(!session.canGoBack)
                Button(action: session.goForward) { Image(systemName: "chevron.right") }
                    .disabled(!session.canGoForward)

                Menu {
                    Button {
                        session.setDesktopMode(true)
                    } label: {
                        Label("Desktop Website", systemImage: session.prefersDesktopMode ? "checkmark.circle.fill" : "desktopcomputer")
                    }
                    Button {
                        session.setDesktopMode(false)
                    } label: {
                        Label("Mobile Website", systemImage: session.prefersDesktopMode ? "iphone" : "checkmark.circle.fill")
                    }
                } label: {
                    Image(systemName: session.prefersDesktopMode ? "desktopcomputer" : "iphone")
                }
                .accessibilityLabel(session.prefersDesktopMode ? "Desktop website mode" : "Mobile website mode")

                Spacer()

                Menu {
                    ForEach(store.tabs) { tab in
                        Button {
                            store.select(tab.id)
                        } label: {
                            Label(tab.title, systemImage: tab.id == store.selectedID ? "checkmark.circle.fill" : "circle")
                        }
                    }
                    Divider()
                    Button {
                        store.createTab()
                    } label: {
                        Label("New Tab", systemImage: "plus")
                    }
                    if store.tabs.count > 1 {
                        Button(role: .destructive) {
                            store.close(session.id)
                        } label: {
                            Label("Close Current Tab", systemImage: "xmark")
                        }
                    }
                } label: {
                    Label("\(store.tabs.count)", systemImage: "square.on.square")
                }

                Button {
                    store.createTab()
                } label: {
                    Image(systemName: "plus")
                }
            }
            .font(.system(size: 17, weight: .semibold))
            .padding(.horizontal, 4)
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 9)
        .background(.regularMaterial)
    }
}
