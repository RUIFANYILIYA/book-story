plugins {
    alias(libs.plugins.android.library)
}

// Canvas rendering: the glyph measurer, the reader view and the page motions. Split from
// `:engine-core` by exactly one rule — nothing in here may be needed to decide *where* a line
// breaks. The layout maths stays pure JVM so it can be regression tested without an emulator;
// this module is the part that genuinely needs a device, and is therefore kept as thin as the
// plan allows.
android {
    namespace = "dev.ebookd.engine.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":engine-core"))
    implementation(libs.androidx.core.ktx)
}
