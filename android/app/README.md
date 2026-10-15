# RiftOS Android application module

`build.gradle.kts` is authoritative for mandatory Kotlin sources, Android ABI filters and verification tasks. `src/main/AndroidManifest.xml` owns processes/services/permissions and protected recovery entrypoints. `src/main/java/com/riftos/app/` owns host API glue and operating services. `src/main/assets/` contains packaged runtime assets; `src/main/cpp/` contains host native services.

Frozen embedded Core/Shell sources remain in this module solely because the current signed-host class closure requires them. Do not grow or version them as active implementations. Tests for independent external Core/Shell go with RiftComponents. Keep Gradle source closure/security checks and user-triggered Builder artifact verification intact.
