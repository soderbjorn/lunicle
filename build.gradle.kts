// Root build script. Every plugin is declared here with `apply false` so the
// version catalog stays the single source of truth for versions; the modules
// opt in individually via `alias(libs.plugins.…)`.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktor) apply false
}

// The lunula toolkit resolves from Maven Central (see gradle/libs.versions.toml
// for the pinned version). During development a sibling lunula checkout is
// picked up automatically as a composite build — see settings.gradle.kts.
