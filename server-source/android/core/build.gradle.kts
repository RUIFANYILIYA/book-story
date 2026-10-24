plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Network, storage, sync and the design system: everything the screens share. It is a library
// module so the paginator's consumers can be tested without the app, and so `:app` stays what the
// plan says it is — screens, navigation and theme.
android {
    namespace = "dev.ebookd.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

// The live-server contract test is skipped unless these are present; CI has no server, so it stays
// green there, and a developer with `npm run dev` running can exercise the real wire in one command.
tasks.withType<Test>().configureEach {
    providers.environmentVariable("EBOOKD_E2E_URL").orNull?.let { systemProperty("ebookd.e2e.url", it) }
    providers.environmentVariable("EBOOKD_E2E_USER").orNull?.let { systemProperty("ebookd.e2e.user", it) }
    providers.environmentVariable("EBOOKD_E2E_PASSWORD").orNull?.let { systemProperty("ebookd.e2e.password", it) }
}

dependencies {
    implementation(project(":engine-core"))

    // --- Design system (versions from the BOM only) ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.runtime.compose)

    // --- Storage ---
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // --- Paging ---
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // --- Network ---
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.kotlinx.serialization.json)

    // --- Background work ---
    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // --- DI ---
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // --- Images ---
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
}
