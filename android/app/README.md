# RiftOS Android App Module

This module builds the native `com.riftos.app` APK and packages the trusted RiftOS web runtime into generated Android assets.

- Build/source synchronization: [`../../docs/systems/build-validation/README.md`](../../docs/systems/build-validation/README.md)
- Android host/lifecycle: [`../../docs/systems/android-host/README.md`](../../docs/systems/android-host/README.md)
- Native Kotlin component map: [`src/main/java/com/riftos/app/README.md`](src/main/java/com/riftos/app/README.md)
- Android resources/theme: [`src/main/res/README.md`](src/main/res/README.md)
- Browser-injected native assets: [`src/main/assets/README.md`](src/main/assets/README.md)

Keep Android framework authority in the owning native subsystem. Do not add duplicate web/runtime behavior to the Gradle module merely because it is packaged here.
