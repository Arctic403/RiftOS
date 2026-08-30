import SwiftUI

struct ContentView: View {
    @StateObject private var browser = RiftBrowserStore()

    var body: some View {
        ZStack {
            RiftOSWebView(browserStore: browser)
                .ignoresSafeArea()
                .background(Color.black)

            if browser.isPresented {
                RiftBrowserView(store: browser)
                    .ignoresSafeArea(edges: .bottom)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                    .zIndex(10)
            }
        }
        .animation(.easeInOut(duration: 0.18), value: browser.isPresented)
    }
}
