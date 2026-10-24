plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// This module is the reading engine's brain: document model, TXT normalisation, chapter
// splitting, TOC codecs and the paginator. It must never import android.* — that is what lets
// `gradlew :engine-core:test` run on a JDK-only machine and keeps the hardest logic unit-testable.
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.juniversalchardet)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty(
        "ebookd.fixtures",
        rootDir.parentFile.resolve("shared/fixtures").invariantSeparatorsPath,
    )
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
