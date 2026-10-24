// The Android modules are only included when an SDK is discoverable.
// :engine-core is a pure Kotlin/JVM module (document model, TXT chapter splitting, pagination)
// and therefore builds and tests on a JDK-only machine — that is how the hardest logic gets
// verified before (or without) an Android toolchain. AGP fails at *configuration* time with no
// sdk.dir, which is exactly why this gate lives in settings.gradle.kts rather than in a module.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ebookd-reader"

val sdkAvailable: Boolean =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        File(rootDir, "local.properties").let { f ->
            f.exists() && f.readText().lineSequence().any { it.startsWith("sdk.dir=") }
        }

include(":engine-core")

// Modules are created one milestone at a time, so a missing directory means "not written yet"
// rather than "broken build".
fun includeModule(name: String) {
    if (File(rootDir, name.replace(':', File.separatorChar)).isDirectory) include(name)
}

if (sdkAvailable) {
    listOf(":core", ":engine-android", ":app").forEach(::includeModule)
} else {
    logger.lifecycle(
        "ebookd: no Android SDK detected (ANDROID_HOME unset and no local.properties). " +
            "Building :engine-core only — run 'gradlew :engine-core:test'.",
    )
}
