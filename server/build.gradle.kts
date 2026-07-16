plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.sqldelight)
    application
}

group = "se.soderbjorn.lunicle"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

// SQLDelight generates typed Kotlin from the .sq files in
// src/main/sqldelight/. It lives in :server and nowhere else, on purpose: the
// database is the *server's*. A client never opens it — it cannot, the file is
// on Railway's volume — and reaches it only through LunicleApi over HTTP. So
// this is a plain JVM use of a library that happens to also support
// multiplatform, and the multiplatform half goes unused.
sqldelight {
    databases {
        create("LunicleDatabase") {
            packageName.set("se.soderbjorn.lunicle.db")

            // The default dialect is SQLite 3.18, which predates both features
            // the schema leans on: UPSERT (`ON CONFLICT DO UPDATE`, 3.24) and
            // `RETURNING` (3.35). Without this the .sq files fail to compile,
            // and the error names the syntax rather than the dialect, which
            // sends you looking in the wrong file.
            //
            // Safe to declare because the engine underneath is whatever
            // sqlite-jdbc bundles, which is far newer than 3.38 — the dialect
            // only governs what SQLDelight's compiler will accept.
            dialect(libs.sqldelight.sqlite338Dialect)

            // Where the committed schema snapshots live — one `<version>.db`
            // per released schema, checked in alongside the .sq files.
            //
            // Setting this is also what *registers* the
            // `generateMainLunicleDatabaseSchema` task; without it the task
            // does not exist and verifyMigrations below has nothing to verify
            // against. That is a confusing failure to meet cold, because the
            // error names the missing .db file rather than the missing setting.
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))

            // Fail the build if a .sq schema change lands without a matching
            // .sqm migration. The volume is the point of this half of the
            // stage: from the first deploy there is data on it that a careless
            // schema edit would silently destroy, and this is the only thing
            // that would notice.
            //
            // The workflow this imposes, for later: change a .sq, add a
            // `<n>.sqm` describing how to get there from version n, then re-run
            // `generateMainLunicleDatabaseSchema` to snapshot the new version.
            verifyMigrations.set(true)
        }
    }
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
    // SQLite, via SQLDelight's JDBC driver. Pulls org.xerial:sqlite-jdbc — and
    // with it the SQLite engine as a bundled native library — in transitively.
    // See the shadowJar note at the bottom of this file.
    implementation(libs.sqldelight.sqliteDriver)
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

    // Where a local run keeps its SQLite file. Under build/ so that `./gradlew
    // clean` discards it: a local database is scratch data, and the ability to
    // throw it away and watch the schema get created from nothing is worth
    // having — it is the one code path production runs exactly once, on a fresh
    // volume, where getting it wrong is most expensive.
    //
    // Deliberately NOT the deployed default (/data/lunicle.db): a developer
    // machine has no such directory, and silently creating one at the
    // filesystem root would be rude. See resolveDatabasePath() in Database.kt.
    val databasePath = providers.gradleProperty("databasePath")
        .getOrElse(layout.buildDirectory.file("lunicle.db").get().asFile.absolutePath)
    systemProperty("lunicle.databasePath", databasePath)

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
