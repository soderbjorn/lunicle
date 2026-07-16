plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    application
}

group = "se.soderbjorn.lunicle"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

// The Kotlin/JS bundle the server serves. Production (minified, DCE'd) rather
// than the development bundle: this one is fetched over the public internet on
// every cold visit, so the ~5x size difference is a real cost, unlike Lunamux's
// loopback-served bundle. Note that Lunamux deliberately ships its *dev* bundle
// to dodge a Kotlin/JS production-optimizer miscompilation (see its
// KOTLIN_JS_PROD_CRASH.html). That bug is layout-sensitive and this codebase is
// a fraction of the size, so production is the right default here — but if the
// counter ever throws "<mangled> is not a function" in the console, that
// investigation is where to start, and the fix is to swap both values below to
// their development equivalents.
val webDistDir = project(":web").layout.buildDirectory.dir("dist/js/productionExecutable")
val webDistTask = ":web:jsBrowserDistribution"
val embeddedWebResourcesDir = layout.buildDirectory.dir("generated/web-resources")

application {
    mainClass.set("se.soderbjorn.lunicle.ApplicationKt")
}

// Stage the web bundle under build/generated/web-resources/web/ so it ends up
// inside the fat jar at /web on the classpath. The packaged server reads it via
// staticResources when no on-disk webDist is provided.
val copyWebDistToResources by tasks.registering(Copy::class) {
    dependsOn(webDistTask)
    from(webDistDir)
    into(embeddedWebResourcesDir.map { it.dir("web") })
}

sourceSets["main"].resources.srcDir(embeddedWebResourcesDir)

tasks.named("processResources") {
    dependsOn(copyWebDistToResources)
}

dependencies {
    implementation(projects.clientServer)
    // The HTTP *client* engine, for calling Google and GitHub during sign-in.
    // clientServer already pulls CIO in for its jvmMain, but as an
    // `implementation` dependency — so it reaches this module's runtime
    // classpath transitively and its compile classpath not at all. Depending on
    // that would mean an unrelated edit in clientServer could turn the engine
    // into a NoClassDefFoundError here, at runtime, during a sign-in. Naming it
    // is a line of build script against a bad afternoon.
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverDefaultHeaders)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}

// `./gradlew :server:run` serves the bundle straight off disk (see the
// `lunicle.webDist` branch in Application.kt) so a web edit needs only a
// `:web:jsBrowserDistribution`, not a re-jar.
tasks.named<JavaExec>("run") {
    dependsOn(webDistTask)
    systemProperty("lunicle.webDist", webDistDir.get().asFile.absolutePath)

    // Framing origin for local runs, passed as `-PframeAncestors=…` (see
    // scripts/dev-local.sh), defaulting to the production value.
    //
    // Always set, even to the default, so that a local run's framing policy is
    // decided entirely by this invocation. The alternative — reading the
    // FRAME_ANCESTORS environment variable here — would be subtly wrong under
    // Gradle: a JavaExec inherits the long-lived *daemon's* environment rather
    // than the invoking shell's, so whatever the daemon happened to start with
    // would apply to every later run. A -P property is per-invocation and
    // configuration-cache-tracked, so it cannot go stale that way. The deployed
    // container still uses the environment variable, where the process is a
    // plain `java -jar` and the environment is exact.
    // See resolveFrameAncestors() in Application.kt.
    val frameAncestors = providers.gradleProperty("frameAncestors")
        .getOrElse("https://lunamux.dev")
    systemProperty("lunicle.frameAncestors", frameAncestors)

    // OAuth credentials, passed by scripts/dev-local.sh from a gitignored .env
    // (see .env.example). Same -P-not-environment reasoning as frameAncestors,
    // and it matters more here: a secret exported into the daemon's environment
    // would survive a rotation and resolve to the stale value until the daemon
    // was killed, which presents as "I changed the secret and nothing happened".
    //
    // Unset is normal, not an error — a developer without credentials gets a
    // server with no sign-in, which is Stage 1 and boots fine. Each property is
    // only forwarded when present so that resolveOAuthConfig()'s blank-is-absent
    // rule never has to distinguish "unset" from "set to empty".
    //
    // See resolveValue() in OAuthConfig.kt for the runtime half.
    listOf(
        "googleClientId",
        "googleClientSecret",
        "githubClientId",
        "githubClientSecret",
    ).forEach { name ->
        val value = providers.gradleProperty(name).getOrElse("")
        if (value.isNotBlank()) systemProperty("lunicle.$name", value)
    }
}
