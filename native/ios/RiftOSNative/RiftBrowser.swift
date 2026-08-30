import SwiftUI
import WebKit
import UIKit
import Combine

extension Notification.Name {
    static let riftBrowserStateDidChange = Notification.Name("RiftBrowserStateDidChange")
}

final class RiftBrowserTabSession: NSObject, ObservableObject, Identifiable, WKNavigationDelegate, WKUIDelegate, WKDownloadDelegate {
    let id: UUID

    @Published var title: String = "New Tab"
    @Published var addressText: String = ""
    @Published var isLoading = false
    @Published var estimatedProgress: Double = 0
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var prefersDesktopMode = true
    @Published var lastError: String?
    @Published var lastDownloadName: String?

    var openNewTab: ((URL) -> Void)?
    var stateChanged: (() -> Void)?

    private var observations: [NSKeyValueObservation] = []
    private var downloadDestinations: [ObjectIdentifier: URL] = [:]

    lazy var webView: WKWebView = {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.defaultWebpagePreferences.preferredContentMode = prefersDesktopMode ? .desktop : .mobile
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        configuration.allowsInlineMediaPlayback = true

        // This is a normal website surface. Never attach RiftNative or any
        // RiftWorkspace message handler to browser tabs.
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        view.uiDelegate = self
        view.allowsBackForwardNavigationGestures = true
        view.scrollView.keyboardDismissMode = .interactive
        if #available(iOS 16.4, *) { view.isInspectable = true }
        return view
    }()

    init(id: UUID = UUID(), desktopMode: Bool = true) {
        self.id = id
        self.prefersDesktopMode = desktopMode
        super.init()
        installObservers()
    }

    deinit {
        observations.forEach { $0.invalidate() }
    }

    func load(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = destination(for: trimmed) else { return }
        lastError = nil
        addressText = url.absoluteString
        webView.load(URLRequest(url: url, cachePolicy: .useProtocolCachePolicy, timeoutInterval: 60))
        stateChanged?()
    }

    func setDesktopMode(_ enabled: Bool, reload: Bool = true) {
        prefersDesktopMode = enabled
        webView.configuration.defaultWebpagePreferences.preferredContentMode = enabled ? .desktop : .mobile
        stateChanged?()
        if reload, webView.url != nil { webView.reload() }
    }

    func goBack() { if webView.canGoBack { webView.goBack() } }
    func goForward() { if webView.canGoForward { webView.goForward() } }
    func reload() { lastError = nil; webView.reload() }
    func stop() { webView.stopLoading() }

    func shareCurrentPage() {
        guard let value = webView.url ?? URL(string: addressText) else { return }
        present(UIActivityViewController(activityItems: [value], applicationActivities: nil))
    }

    func promptFindOnPage() {
        let alert = UIAlertController(title: "Find on Page", message: nil, preferredStyle: .alert)
        alert.addTextField { field in
            field.placeholder = "Text to find"
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Find", style: .default) { [weak self, weak alert] _ in
            guard let self, let text = alert?.textFields?.first?.text, !text.isEmpty else { return }
            let configuration = WKFindConfiguration()
            configuration.caseSensitive = false
            configuration.backwards = false
            configuration.wraps = true
            self.webView.find(text, configuration: configuration) { result in
                if !result.matchFound {
                    self.presentMessage(title: "Not Found", message: "No match for “\(text)”.")
                }
            }
        })
        present(alert)
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
                DispatchQueue.main.async {
                    self?.title = (view.title?.isEmpty == false ? view.title! : "New Tab")
                    self?.stateChanged?()
                }
            },
            view.observe(\.url, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async {
                    self?.addressText = view.url?.absoluteString ?? self?.addressText ?? ""
                    self?.stateChanged?()
                }
            },
            view.observe(\.isLoading, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async { self?.isLoading = view.isLoading }
            },
            view.observe(\.estimatedProgress, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async { self?.estimatedProgress = view.estimatedProgress }
            },
            view.observe(\.canGoBack, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async {
                    self?.canGoBack = view.canGoBack
                    self?.stateChanged?()
                }
            },
            view.observe(\.canGoForward, options: [.initial, .new]) { [weak self] view, _ in
                DispatchQueue.main.async {
                    self?.canGoForward = view.canGoForward
                    self?.stateChanged?()
                }
            }
        ]
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        preferences: WKWebpagePreferences,
        decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void
    ) {
        guard let url = navigationAction.request.url, let scheme = url.scheme?.lowercased() else {
            decisionHandler(.allow, preferences)
            return
        }

        if navigationAction.shouldPerformDownload {
            decisionHandler(.download, preferences)
            return
        }

        if ["http", "https", "about", "blob", "data"].contains(scheme) {
            preferences.preferredContentMode = prefersDesktopMode ? .desktop : .mobile
            lastError = nil
            decisionHandler(.allow, preferences)
        } else {
            if UIApplication.shared.canOpenURL(url) { UIApplication.shared.open(url) }
            decisionHandler(.cancel, preferences)
        }
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
    ) {
        let response = navigationResponse.response
        let contentDisposition = (response as? HTTPURLResponse)?
            .value(forHTTPHeaderField: "Content-Disposition")?
            .lowercased() ?? ""
        if navigationResponse.canShowMIMEType == false || contentDisposition.contains("attachment") {
            decisionHandler(.download)
        } else {
            decisionHandler(.allow)
        }
    }

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            openNewTab?(url)
        }
        return nil
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        download.delegate = self
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        download.delegate = self
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        recordNavigationError(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        recordNavigationError(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        lastError = "The page process stopped. Reload the page to continue."
    }

    func download(
        _ download: WKDownload,
        decideDestinationUsing response: URLResponse,
        suggestedFilename: String,
        completionHandler: @escaping (URL?) -> Void
    ) {
        do {
            let destination = try uniqueDownloadURL(suggestedFilename: suggestedFilename)
            downloadDestinations[ObjectIdentifier(download)] = destination
            completionHandler(destination)
        } catch {
            lastError = "Download failed: \(error.localizedDescription)"
            completionHandler(nil)
        }
    }

    func downloadDidFinish(_ download: WKDownload) {
        if let destination = downloadDestinations.removeValue(forKey: ObjectIdentifier(download)) {
            lastDownloadName = destination.lastPathComponent
            stateChanged?()
        }
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        downloadDestinations.removeValue(forKey: ObjectIdentifier(download))
        lastError = "Download failed: \(error.localizedDescription)"
    }

    private func uniqueDownloadURL(suggestedFilename: String) throws -> URL {
        try RiftWorkspace.shared.ensureLayout()
        let invalid = CharacterSet(charactersIn: "/\\:\0")
        let safe = suggestedFilename
            .components(separatedBy: invalid)
            .filter { !$0.isEmpty }
            .joined(separator: "-")
        let filename = safe.isEmpty ? "download" : safe
        let folder = try RiftWorkspace.shared.url(for: "downloads", allowRoot: false)
        let ext = (filename as NSString).pathExtension
        let stem = (filename as NSString).deletingPathExtension
        var candidate = folder.appendingPathComponent(filename)
        var counter = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            let numbered = ext.isEmpty ? "\(stem)-\(counter)" : "\(stem)-\(counter).\(ext)"
            candidate = folder.appendingPathComponent(numbered)
            counter += 1
        }
        return candidate
    }

    private func recordNavigationError(_ error: Error) {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled { return }
        lastError = error.localizedDescription
    }

    private func presentMessage(title: String, message: String) {
        present(UIAlertController(title: title, message: message, preferredStyle: .alert), addOK: true)
    }

    private func present(_ controller: UIViewController, addOK: Bool = false) {
        if addOK, let alert = controller as? UIAlertController {
            alert.addAction(UIAlertAction(title: "OK", style: .default))
        }
        guard
            let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
            let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController
        else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        if let popover = controller.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.maxY - 1, width: 1, height: 1)
        }
        top.present(controller, animated: true)
    }
}

final class RiftBrowserStore: ObservableObject {
    private struct SavedTab: Codable {
        let id: UUID
        let url: String
        let desktop: Bool
    }

    private struct SavedState: Codable {
        let selectedID: UUID?
        let tabs: [SavedTab]
    }

    @Published private(set) var tabs: [RiftBrowserTabSession] = []
    @Published var selectedID: UUID?
    @Published var isPresented = false

    private let stateKey = "RiftBrowser.NativeSession.v1"
    private var restoring = false

    init() {
        restoreSession()
    }

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
        publishState()
    }

    @discardableResult
    func createTab(_ url: String = "https://chatgpt.com", id: UUID = UUID(), desktopMode: Bool = true) -> RiftBrowserTabSession {
        let tab = RiftBrowserTabSession(id: id, desktopMode: desktopMode)
        wire(tab)
        tabs.append(tab)
        selectedID = tab.id
        tab.load(url)
        publishState()
        return tab
    }

    func select(_ id: UUID) {
        guard tabs.contains(where: { $0.id == id }) else { return }
        selectedID = id
        isPresented = true
        publishState()
    }

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
        publishState()
    }

    func closeBrowser() {
        isPresented = false
        publishState()
    }

    func navigate(_ url: String, tabID: UUID? = nil) {
        if let tabID { select(tabID) }
        selected?.load(url)
    }

    func setDesktopMode(_ enabled: Bool, tabID: UUID? = nil) {
        if let tabID { select(tabID) }
        selected?.setDesktopMode(enabled)
        publishState()
    }

    func goBack() { selected?.goBack() }
    func goForward() { selected?.goForward() }
    func reload() { selected?.reload() }
    func stop() { selected?.stop() }
    func share() { selected?.shareCurrentPage() }
    func findOnPage() { selected?.promptFindOnPage() }

    func snapshot() -> [String: Any] {
        let rows: [[String: Any]] = tabs.map { tab in
            [
                "id": tab.id.uuidString,
                "title": tab.title,
                "url": tab.webView.url?.absoluteString ?? tab.addressText,
                "desktop": tab.prefersDesktopMode,
                "canGoBack": tab.canGoBack,
                "canGoForward": tab.canGoForward,
                "loading": tab.isLoading
            ]
        }
        return [
            "selectedID": selectedID?.uuidString ?? NSNull(),
            "presented": isPresented,
            "tabs": rows
        ]
    }

    private func wire(_ tab: RiftBrowserTabSession) {
        tab.openNewTab = { [weak self] url in
            DispatchQueue.main.async { self?.createTab(url.absoluteString) }
        }
        tab.stateChanged = { [weak self] in
            DispatchQueue.main.async { self?.publishState() }
        }
    }

    private func restoreSession() {
        guard
            let data = UserDefaults.standard.data(forKey: stateKey),
            let saved = try? JSONDecoder().decode(SavedState.self, from: data),
            !saved.tabs.isEmpty
        else { return }

        restoring = true
        for row in saved.tabs.prefix(12) {
            let tab = RiftBrowserTabSession(id: row.id, desktopMode: row.desktop)
            wire(tab)
            tabs.append(tab)
            tab.load(row.url)
        }
        selectedID = saved.selectedID.flatMap { id in tabs.contains(where: { $0.id == id }) ? id : nil } ?? tabs.first?.id
        restoring = false
        publishState()
    }

    private func persistSession() {
        guard !restoring else { return }
        let savedTabs = tabs.prefix(12).map {
            SavedTab(id: $0.id, url: $0.webView.url?.absoluteString ?? $0.addressText, desktop: $0.prefersDesktopMode)
        }
        let state = SavedState(selectedID: selectedID, tabs: Array(savedTabs))
        if let data = try? JSONEncoder().encode(state) {
            UserDefaults.standard.set(data, forKey: stateKey)
        }
    }

    private func publishState() {
        persistSession()
        NotificationCenter.default.post(name: .riftBrowserStateDidChange, object: self)
    }
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
                ZStack {
                    RiftBrowserWebView(session: selected)
                        .id(selected.id)
                    if let error = selected.lastError, !selected.isLoading {
                        VStack(spacing: 12) {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.system(size: 30, weight: .semibold))
                            Text("Page couldn’t load")
                                .font(.headline)
                            Text(error)
                                .font(.footnote)
                                .multilineTextAlignment(.center)
                                .foregroundStyle(.secondary)
                            Button("Reload") { selected.reload() }
                                .buttonStyle(.borderedProminent)
                        }
                        .padding(24)
                        .frame(maxWidth: 420)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
                        .padding()
                    }
                }
                .overlay(alignment: .bottom) {
                    if let name = selected.lastDownloadName {
                        Label("Saved to RiftWorkspace/downloads/\(name)", systemImage: "arrow.down.circle.fill")
                            .font(.caption)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(.regularMaterial, in: Capsule())
                            .padding(.bottom, 8)
                    }
                }
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
                    Button { session.setDesktopMode(true) } label: {
                        Label("Desktop Website", systemImage: session.prefersDesktopMode ? "checkmark.circle.fill" : "desktopcomputer")
                    }
                    Button { session.setDesktopMode(false) } label: {
                        Label("Mobile Website", systemImage: session.prefersDesktopMode ? "iphone" : "checkmark.circle.fill")
                    }
                    Divider()
                    Button(action: session.promptFindOnPage) {
                        Label("Find on Page", systemImage: "text.magnifyingglass")
                    }
                    Button(action: session.shareCurrentPage) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                } label: {
                    Image(systemName: session.prefersDesktopMode ? "desktopcomputer" : "iphone")
                }
                .accessibilityLabel("Page actions")

                Spacer()

                Menu {
                    ForEach(store.tabs) { tab in
                        Button { store.select(tab.id) } label: {
                            Label(tab.title, systemImage: tab.id == store.selectedID ? "checkmark.circle.fill" : "circle")
                        }
                    }
                    Divider()
                    Button { store.createTab() } label: {
                        Label("New Tab", systemImage: "plus")
                    }
                    Button(role: .destructive) { store.close(session.id) } label: {
                        Label("Close Current Tab", systemImage: "xmark")
                    }
                } label: {
                    Label("\(store.tabs.count)", systemImage: "square.on.square")
                }

                Button { store.createTab() } label: {
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
