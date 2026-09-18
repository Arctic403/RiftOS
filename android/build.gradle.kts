buildscript {
    dependencies {
        // AGP 9 built-in Kotlin defaults lower; QuickJS 1.0.14 is published with Kotlin 2.4 metadata.
        // Pin the built-in Kotlin compiler/runtime toolchain high enough to consume that metadata.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.0" apply false
}
