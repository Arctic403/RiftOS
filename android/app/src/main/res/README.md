# RiftOS Android Resources

Android resources in this tree support the native APK host rather than implementing independent application logic.

- `values/styles.xml` defines the native Activity/window theme and system-bar/window-background defaults used before/around the trusted RiftOS shell. Ownership is shared by [`../../../../../docs/systems/android-host/README.md`](../../../../../docs/systems/android-host/README.md) and [`../../../../../docs/systems/shell-ui/README.md`](../../../../../docs/systems/shell-ui/README.md).

Keep visual shell layout in web CSS unless the value must exist at the Android window/theme level before WebView content is available.
