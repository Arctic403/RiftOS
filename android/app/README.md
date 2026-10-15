# RiftOS Android application module

`build.gradle.kts` is authoritative for mandatory Kotlin sources, Android ABI filters and verification tasks. `src/main/AndroidManifest.xml` owns processes/services/permissions and protected recovery entrypoints. `src/main/java/com/riftos/app/` owns host API glue and operating services. `src/main/assets/` contains packaged runtime assets; `src/main/cpp/` contains host native services.

The embedded Core/Shell implementation sources were removed from this source candidate; only the external component host ABI/IPC/Recovery and generic Android services remain. Tests for independent external Core/Shell go with RiftComponents. Keep Gradle source closure/security checks and user-triggered Builder artifact verification intact.
