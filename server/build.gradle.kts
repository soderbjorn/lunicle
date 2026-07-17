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

            // OFF, and replaced — not abandoned. MigrationTest does this job
            // instead; see server/src/test/kotlin/…/MigrationTest.kt.
            //
            // This task cannot complete on this schema. It compares the two
            // catalogs with java-object-diff, which walks *paths* through an
            // object graph rather than nodes — and schemacrawler's catalog is
            // cyclic (a table holds its columns, a column points back at its
            // table, a foreign key points at both). With the three loosely
            // coupled tables this schema used to have, that was fine. With
            // thirteen densely cross-referenced ones — projects ← labels,
            // components, statuses, issues, project_roles; issues ←
            // issue_labels, issue_components, comments, attachments — the path
            // count explodes and the task runs for tens of minutes without
            // finishing. Verified by thread dump: hundreds of frames deep in
            // de.danielbechler.diff, at a steady 100% of one core.
            //
            // Leaving it on would be worse than useless: `check` depends on it,
            // so `./gradlew build` would hang rather than fail — the one
            // outcome nobody investigates, because it looks like a slow build.
            //
            // The workflow is unchanged and still enforced, just by a test:
            // change a .sq, add a `<n>.sqm` describing how to get there from
            // version n, then re-run `generateMainLunicleDatabaseSchema` to
            // snapshot the new version. Skip any of that and MigrationTest
            // fails, naming what drifted.
            //
            // NOTE: this flag alone is not enough — see the task-disabling block
            // at the bottom of this file. The plugin registers and wires up the
            // verify task regardless of what this is set to.
            verifyMigrations.set(false)
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

    // Which port a local run binds, as `-Pport=9000`. Only forwarded when
    // given, so the unset case stays resolvePort()'s own default rather than
    // being decided twice in two files.
    //
    // A property rather than the PORT environment variable, for the reason
    // resolvePort() documents: a JavaExec inherits the Gradle *daemon's*
    // environment, so `PORT=9000 ./gradlew :server:run` is silently ignored.
    // The deployed container still uses PORT — Railway sets it, and there is no
    // daemon there.
    providers.gradleProperty("port").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("lunicle.port", it) }

    // Where a local run keeps its SQLite file, and — beside it — its
    // attachments. This is the local stand-in for Railway's mounted volume:
    // the server derives the attachment directory from the database's own path
    // (see DatabaseLocation.attachmentsDirectory), so naming this one path is
    // all it takes to get the same layout locally as in the container.
    //
    // `~/.lunicle/`, and the two things it is NOT are the point.
    //
    // Not under build/: `clean` is what you run to fix a *build* problem, and it
    // would take every issue you had typed in with it, silently, as a side
    // effect of an unrelated command. Wiping is something you ask for by name:
    // ./scripts/local-db.sh wipe.
    //
    // Not in the repo either, which is where it used to be (`.localdata/`).
    // Real data you typed in has no business inside a checkout: it rides along
    // with every `rm -rf` of a clone, every fresh clone starts empty, and a
    // gitignore entry is the only thing standing between it and a commit. The
    // home directory is where a developer's own data already lives, and it
    // survives all of that. Two checkouts now share one database — deliberately;
    // if you want them apart, pass -PdatabasePath (or LUNICLE_LOCAL_DATA to the
    // script).
    //
    // Also deliberately NOT the deployed default (/data/lunicle.db): a
    // developer machine has no such directory, and silently creating one at the
    // filesystem root would be rude. See resolveDatabaseLocation() in
    // Database.kt.
    val databasePath = providers.gradleProperty("databasePath")
        .getOrElse(File(System.getProperty("user.home"), ".lunicle/lunicle.db").absolutePath)
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

// Take SQLDelight's migration verifier out of `check`, and therefore out of
// `./gradlew build`.
//
// `verifyMigrations.set(false)` above is NOT sufficient and this is the trap:
// the plugin registers `verifyMainLunicleDatabaseMigration` whenever
// `schemaOutputDirectory` is set — which it must be, since that is also what
// registers `generateMainLunicleDatabaseSchema` — and wires it into `check`
// regardless of the flag. So the flag reads like it turns the check off, and
// `./gradlew build` still runs it.
//
// That matters here because the task cannot *finish* on this schema: it diffs
// the two catalogs with java-object-diff, which walks paths through a cyclic
// object graph, and thirteen densely cross-referenced tables make that
// explode. The failure mode is the worst available — `build` hangs at 100% of
// one core rather than failing, which reads as a slow build rather than a
// broken one, and nobody investigates a slow build for eight minutes before
// giving up.
//
// MigrationTest checks the same fact in milliseconds. See its preamble, and the
// verifyMigrations comment above.
tasks.matching { it.name == "verifyMainLunicleDatabaseMigration" }.configureEach {
    enabled = false
}
