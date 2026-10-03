plugins {
    id("com.android.application")
}

android {
    namespace = "com.riftbuild.tools.kotlinc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.riftbuild.tools.kotlinc.payload"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.4.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            pickFirsts += setOf(
                "kotlin/annotation/annotation.kotlin_builtins",
                "kotlin/collections/collections.kotlin_builtins",
                "kotlin/concurrent/atomics/atomics.kotlin_builtins",
                "kotlin/coroutines/coroutines.kotlin_builtins",
                "kotlin/internal/internal.kotlin_builtins",
                "kotlin/kotlin.kotlin_builtins",
                "kotlin/ranges/ranges.kotlin_builtins",
                "kotlin/reflect/reflect.kotlin_builtins"
            )
        }
    }
}

dependencies {
    implementation("com.github.PranavPurwar:kotlinc-android:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
