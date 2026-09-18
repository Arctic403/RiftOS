# RiftOS Android Resources

Android resources in this tree support the native APK host and Android-owned RiftOS desktop surfaces.

- `values/styles.xml` defines the Activity/window theme plus system-bar and window-background defaults used by the native desktop.
- Browser/Chromium rendering is owned only by explicit `RiftBrowser*` sources; resource files must not recreate a shell WebView or WebView-backed desktop.
- Native desktop layout belongs in Android views/resources. Browser content styling belongs to RiftBrowser-owned web content only.
