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
    // The Firestore backend (LNL-111) and its GCS attachment store. The BOM keeps
    // the Firestore client's gRPC/protobuf/gax/auth transitive set aligned. These
    // jars ship in the one image but are inert on the SQLite path: nothing
    // constructs a client unless LUNICLE_DB_BACKEND=firestore selects it, so ADC
    // and the metadata server are never touched on Railway. See FirestoreProvider.
    implementation(platform(libs.google.cloud.bom))
    implementation(libs.google.cloud.firestore)
    implementation(libs.google.cloud.storage)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Angus Mail (Jakarta Mail RI) backs the SMTP email transport — Ktor has no
    // SMTP client, so the Resend HTTP path and this share only the
    // EmailTransport seam, not a wire. It brings jakarta.mail-api and the Angus
    // activation impl in transitively; those carry the content-handler and
    // service-provider resources (META-INF/mailcap, META-INF/services/*) that
    // MimeMultipart relies on, and shadowJar preserves them because each is
    // provided by exactly one jar — nothing to merge. See SmtpEmailTransport.
    implementation(libs.angus.mail)
    implementation(libs.logback)
    testImplementation(libs.ktor.serverTestHost)
    // Lets a test hand EmailSender an engine that answers from a lambda, so the
    // one claim that matters about send_email — who the message actually goes
    // to — can be asserted without a Resend key or a real inbox.
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.client.serializationKotlinxJson)
    testImplementation(libs.kotlin.testJunit)
}

// The Firestore contract tests (LNL-111) run against the emulator, reached via
// FIRESTORE_EMULATOR_HOST. Forwarded from the per-invocation system property
// `-Dlunicle.firestoreEmulatorHost=…` rather than read from the environment, for
// the daemon reason every other resolver here documents: a `Test` task inherits
// the long-lived Gradle daemon's environment, so an env var set in the shell that
// launched gradle would go stale. When the property is absent, the variable is
// unset in the test JVM and the Firestore contract tests skip themselves (see
// FirestoreContractFixture) — so the SQLite suite still runs with no emulator.
tasks.named<Test>("test") {
    providers.systemProperty("lunicle.firestoreEmulatorHost").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { environment("FIRESTORE_EMULATOR_HOST", it) }
}

// `./gradlew :server:run` serves the bundle straight off disk (see the
// `lunicle.webDist` branch in Application.kt) so a web edit needs only a
// `:web:jsBrowserDistribution`, not a re-jar.
tasks.named<JavaExec>("run") {
    dependsOn(webDistTask)
    systemProperty("lunicle.webDist", webDistDir.get().asFile.absolutePath)

    // Framing origin for local runs, passed as `-PallowedFrameAncestors=…` (see
    // scripts/lib/dev-server.sh). Only forwarded when given: a bare
    // `./gradlew :server:run` gets the server's own 'self'-only framing, and a
    // local embed test names the origin explicitly through the script.
    //
    // A -P property rather than the LUNICLE_ALLOWED_FRAME_ANCESTORS environment
    // variable, for the reason resolvePort() documents: a JavaExec inherits the
    // long-lived *daemon's* environment rather than the invoking shell's, so
    // whatever the daemon happened to start with would apply to every later run.
    // A -P property is per-invocation and configuration-cache-tracked, so it
    // cannot go stale that way. The deployed container still uses the environment
    // variable, where the process is a plain `java -jar` and the environment is
    // exact. See resolveAllowedFrameAncestors() in Application.kt.
    providers.gradleProperty("allowedFrameAncestors").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("lunicle.allowedFrameAncestors", it) }

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

    // The brand directory for a local run, as `-PbrandDir=/path/to/brand` — the
    // local stand-in for LUNICLE_BRAND_DIR on the deployed container (LNL-110, see
    // resolveBrandDir()). Same -P-not-environment reasoning as the properties
    // above: an environment variable would be read from the daemon's environment,
    // not the invoking shell's, and go stale. Unset is the ordinary state —
    // branding off, the default look. Only forwarded when given.
    providers.gradleProperty("brandDir").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("lunicle.brandDir", it) }

    // OAuth credentials, passed by scripts/lib/dev-server.sh from a gitignored .env
    // (see .env.example). Same -P-not-environment reasoning as allowedFrameAncestors,
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
    //
    // The email properties ride the exact same mechanism, and for the same
    // reason readEmailEnv() reads a system property before an environment
    // variable: a local run can be pointed at a mail server — a Mailpit on
    // localhost, or a real relay — with `-PsmtpHost=… -PsmtpUsername=…
    // -PsmtpPassword=… -PemailFrom=…`, without any of it leaking into the
    // daemon's environment. Unset is the ordinary local state (email off, which
    // boots fine); each is forwarded only when present so chooseEmailTransport's
    // blank-is-absent rule never has to tell "unset" from "set to empty". See
    // EmailTransport.kt.
    listOf(
        "googleClientId",
        "googleClientSecret",
        "resendApiKey",
        "emailFrom",
        "smtpHost",
        "smtpPort",
        "smtpUsername",
        "smtpPassword",
        "smtpTls",
        "emailTransport",
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
