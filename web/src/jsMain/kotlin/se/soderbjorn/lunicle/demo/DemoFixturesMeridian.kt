/**
 * The third seeded project the demo opens with: **Meridian**, an open-source
 * Kotlin Multiplatform storage library.
 *
 * Where the Amiga project is a team planning in sprints and [seedKlinikProject] is
 * a workshop working a queue, this is a public repository's issue tracker — and it
 * is shaped by neither a timebox nor a bench, but by **releases**. It has no
 * sprints at all; what an issue is scheduled against is a version, and the board's
 * rhythm is 0.9.4 → 1.0.0 → 1.1.0 → 2.0.0.
 *
 * Two things live here that no other demo board can show:
 *
 *  - **Outside contributors.** Most people who file here hold `create_issue` and
 *    `comment_on_issue` and nothing else — they cannot move a card, and cannot be
 *    assigned. That asymmetry is what an open tracker actually looks like, and it
 *    is worth a visitor seeing that Lunicle expresses it.
 *  - **Agents.** Several issues and comments carry an `agentName`, because on this
 *    project an agent triages the inbox, files regressions it finds while running
 *    the benchmarks, and posts bisect results. That is the workflow Lunicle's MCP
 *    server exists for, so the demo ought to contain one.
 *
 * Meridian itself is invented but ordinary: a typed key–value store with a
 * coroutine API and `Flow` observation, published for JVM, Android, the Apple
 * targets, JS, Wasm and native. Its bug reports are the bugs multiplatform
 * libraries really get — a target missing from the published artifacts, a linker
 * failure on a new Kotlin version, a Gradle plugin that breaks the configuration
 * cache.
 *
 * `requireLabel` is on: an open tracker that does not make people say whether they
 * are reporting a bug or asking for a feature drowns within a month.
 *
 * @see seedDemoWorld
 * @see seedKlinikProject
 * @see DemoWorld
 */
package se.soderbjorn.lunicle.demo

import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.NotificationKind

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/**
 * Seed the open-source library project into an existing world.
 *
 * Called by [seedDemoWorld] once the demo user exists, so that user can own this
 * board as well.
 *
 * @param w the world being seeded; its user list, project list and notifications
 *   are all appended to.
 * @see seedDemoWorld
 */
internal fun seedMeridianProject(w: DemoWorld) {
    fun daysAgo(n: Int): Long = w.startedAt - n * DAY_MS
    fun hoursAgo(n: Int): Long = w.startedAt - n * HOUR_MS

    // ── People ────────────────────────────────────────────────────────────────
    //
    // Four maintainers and a handful of contributors who file, comment, and are
    // never assigned anything — see the file preamble on why that asymmetry matters.

    fun user(name: String, mailbox: String, domain: String = "meridian.dev"): DemoUser {
        val u = DemoUser(w.allocId(), name, "$mailbox@$domain", AuthProvider.EMAIL)
        w.users.add(u)
        return u
    }

    val janeway = w.demoUser
    val tova = user("Tova Lindgren", "tova")
    val rafael = user("Rafael Duarte", "rafael")
    val wen = user("Wen Li", "wen")
    val ada = user("Ada Brenner", "ada")

    val kasper = user("Kasper Holm", "kasper", domain = "example.com")
    val yara = user("Yara Osei", "yara", domain = "example.com")
    val sofia = user("Sofia Marchetti", "sofia", domain = "example.com")
    val jonas = user("Jonas Weber", "jonas", domain = "example.com")

    // ── The project ───────────────────────────────────────────────────────────

    val p = DemoProject(
        id = w.allocId(),
        name = "Meridian KMP",
        prefix = "MRD",
        discussionsEnabled = false,
        messagesEnabled = false,
        // An open tracker makes people categorise what they are filing.
        requireLabel = true,
        // Who reported it matters when most reporters are strangers.
        showIssueAuthor = true,
    )
    w.projects.add(p)

    // Statuses. "Needs info" is the one an open project cannot do without: the
    // holding pen for a report nobody can act on until the reporter comes back.
    fun status(name: String, pos: Int, req: Boolean = false): DemoStatus {
        val s = DemoStatus(w.allocId(), name, pos, requiresResolution = req)
        p.statuses.add(s)
        return s
    }
    val stTriage = status("Triage", 0)
    val stNeedsInfo = status("Needs info", 1)
    val stAccepted = status("Accepted", 2)
    val stProgress = status("In progress", 3)
    val stReview = status("In review", 4)
    val stClosed = status("Closed", 5, req = true)

    // Priorities — severity, the way a library's tracker words it.
    fun priority(name: String, pos: Int): DemoStatus {
        val s = DemoStatus(w.allocId(), name, pos)
        p.priorities.add(s)
        return s
    }
    val prBlocker = priority("Blocker", 0)
    val prMajor = priority("Major", 1)
    val prMinor = priority("Minor", 2)
    val prTrivial = priority("Trivial", 3)

    // Resolutions. "Stale" is how an open tracker closes a report whose author
    // never came back — honest, and not the same thing as "Won't fix".
    fun resolution(name: String, pos: Int, done: Boolean = false): DemoStatus {
        val s = DemoStatus(w.allocId(), name, pos, isDone = done)
        p.resolutions.add(s)
        return s
    }
    val rsFixed = resolution("Fixed", 0, done = true)
    val rsWontFix = resolution("Won't fix", 1)
    val rsWorksAsIntended = resolution("Works as intended", 2)
    val rsDuplicate = resolution("Duplicate", 3)
    val rsStale = resolution("Stale", 4)

    fun label(name: String, pos: Int): DemoNamed {
        val v = DemoNamed(w.allocId(), name, pos)
        p.labels.add(v)
        return v
    }
    val lbBug = label("Bug", 0)
    val lbFeature = label("Feature", 1)
    val lbDocs = label("Documentation", 2)
    val lbGoodFirst = label("Good first issue", 3)
    val lbBreaking = label("Breaking change", 4)
    val lbRegression = label("Regression", 5)

    // Components — the source sets and artifacts, which is how a multiplatform
    // library's issues actually sort themselves.
    fun component(name: String, pos: Int): DemoNamed {
        val v = DemoNamed(w.allocId(), name, pos)
        p.components.add(v)
        return v
    }
    val cmCore = component("Core", 0)
    val cmGradle = component("Gradle plugin", 1)
    val cmApple = component("Apple targets", 2)
    val cmWeb = component("JS & Wasm", 3)
    val cmAndroid = component("Android", 4)
    val cmJvm = component("JVM", 5)
    val cmDocs = component("Docs", 6)

    // Versions — the milestones this board runs on, in place of sprints.
    fun version(name: String, pos: Int): DemoNamed {
        val v = DemoNamed(w.allocId(), name, pos)
        p.versions.add(v)
        return v
    }
    val ver094 = version("0.9.4", 0)
    val ver100 = version("1.0.0", 1)
    val ver110 = version("1.1.0", 2)
    val ver200 = version("2.0.0-alpha", 3)

    // No sprints: releases are the rhythm here. See the file preamble.

    // Membership. Janeway owns the instance and therefore the board; Tova is the
    // lead maintainer; the contributors may file and talk, and nothing else.
    p.members[janeway.id] = DemoRungKeys.OWNER
    p.members[tova.id] = DemoRungKeys.ADMIN
    listOf(rafael, wen, ada).forEach { p.members[it.id] = DemoRungKeys.MAINTAINER }
    listOf(kasper, yara, sofia).forEach { p.members[it.id] = DemoRungKeys.CONTRIBUTOR }
    p.members[jonas.id] = DemoRungKeys.CONTRIBUTOR

    // ── Issues ────────────────────────────────────────────────────────────────
    //
    // Versions rather than sprints: `plannedVersion` is what a milestone means on
    // this board, and `fixedVersion` is the release something actually shipped in.

    var order = 0.0

    fun issue(
        title: String,
        description: String,
        status: DemoStatus,
        priority: DemoStatus,
        author: DemoUser,
        assignee: DemoUser? = null,
        labels: List<DemoNamed>,
        components: List<DemoNamed> = emptyList(),
        resolution: DemoStatus? = null,
        plannedVersion: DemoNamed? = null,
        fixedVersion: DemoNamed? = null,
        createdDaysAgo: Int,
        updatedDaysAgo: Int = createdDaysAgo,
        agentName: String? = null,
    ): DemoIssue {
        val created = daysAgo(createdDaysAgo)
        val issue = DemoIssue(
            id = w.allocId(),
            number = p.nextNumber++,
            title = title,
            description = description,
            statusId = status.id,
            priorityId = priority.id,
            resolutionId = resolution?.id,
            authorId = author.id,
            agentName = agentName,
            assigneeId = assignee?.id,
            plannedVersionId = plannedVersion?.id,
            fixedVersionId = fixedVersion?.id,
            isDraft = false,
            createdAt = created,
            updatedAt = daysAgo(updatedDaysAgo),
            sortIndex = order++,
        )
        issue.labelIds.addAll(labels.map { it.id })
        issue.componentIds.addAll(components.map { it.id })
        p.issues.add(issue)
        p.events.add(DemoEvent(w.allocId(), issue.id, IssueEventKind.CREATED, authorId = author.id, createdAt = created))
        return issue
    }

    fun comment(issue: DemoIssue, author: DemoUser, body: String, daysAgoValue: Int, agentName: String? = null) {
        p.comments.add(DemoComment(w.allocId(), issue.id, body, author.id, agentName, daysAgo(daysAgoValue)))
    }

    fun event(
        issue: DemoIssue,
        kind: IssueEventKind,
        author: DemoUser,
        daysAgoValue: Int,
        value: String? = null,
        agentName: String? = null,
    ) {
        p.events.add(
            DemoEvent(w.allocId(), issue.id, kind, value, emptyList(), author.id, agentName, daysAgo(daysAgoValue)),
        )
    }

    // ── Closed — shipped in 0.9.4 and 1.0.0 ───────────────────────────────────

    val issWasmLink = issue(
        title = "wasmJs target fails to link: unresolved symbol from the core source set",
        description = "Building against 0.9.3 with the wasmJs target fails at link time with an unresolved " +
            "reference from `meridian-core`. The JS target on the same source set links fine, which points at " +
            "something in the Wasm-specific actual rather than the common code.",
        status = stClosed, priority = prBlocker, author = kasper, assignee = wen,
        labels = listOf(lbBug), components = listOf(cmWeb, cmCore), resolution = rsFixed,
        fixedVersion = ver094, createdDaysAgo = 52, updatedDaysAgo = 44,
    )
    event(issWasmLink, IssueEventKind.ASSIGNEE_CHANGED, tova, 51, value = "Wen Li")
    event(issWasmLink, IssueEventKind.STATUS_CHANGED, wen, 44, value = "Closed")
    comment(issWasmLink, wen, "The Wasm actual was still declared `external`, left over from when the target was a prototype. Removed, and the linker is happy. Thanks for the clean reproducer, Kasper — it took ten minutes because of it.", 45)

    val issMissingSimulator = issue(
        title = "iosSimulatorArm64 is missing from the published artifacts",
        description = "0.9.3 publishes iosArm64 and iosX64, but not iosSimulatorArm64, so the library cannot be " +
            "resolved when building for the simulator on an Apple Silicon machine. It looks like an omission in " +
            "the publication list rather than anything in the code.",
        status = stClosed, priority = prBlocker, author = sofia, assignee = rafael,
        labels = listOf(lbBug), components = listOf(cmApple), resolution = rsFixed,
        fixedVersion = ver094, createdDaysAgo = 50, updatedDaysAgo = 43,
    )
    event(issMissingSimulator, IssueEventKind.STATUS_CHANGED, rafael, 43, value = "Closed")
    comment(issMissingSimulator, rafael, "You're right, it was never in the list. Added, and I've added a check to the release script so a missing target fails the build rather than shipping quietly.", 44)

    val issFlowMissedWrite = issue(
        title = "Flow observation misses writes made inside the same transaction",
        description = "A `Flow` returned by `observe(key)` does not emit for a write performed in the transaction " +
            "the observer itself is inside. Writes from other transactions arrive normally, so the notification " +
            "is being dispatched on commit and the in-transaction reader never sees its own write.",
        status = stClosed, priority = prMajor, author = yara, assignee = tova,
        labels = listOf(lbBug), components = listOf(cmCore), resolution = rsFixed,
        fixedVersion = ver100, createdDaysAgo = 47, updatedDaysAgo = 33,
    )
    event(issFlowMissedWrite, IssueEventKind.STATUS_CHANGED, tova, 33, value = "Closed")
    comment(issFlowMissedWrite, tova, "Confirmed, and it is exactly as you describe. Emissions now go through a per-transaction buffer that flushes to the observer immediately for its own writes and on commit for everyone else's.", 36)
    comment(issFlowMissedWrite, yara, "Verified against 1.0.0-rc2 in our app. Thank you for the quick turnaround.", 33)

    val issConfigCache = issue(
        title = "Gradle plugin is incompatible with the configuration cache",
        description = "Enabling the configuration cache fails the build: the plugin reads the project at execution " +
            "time from inside a task action. Everything it needs could be captured as inputs at configuration time.",
        status = stClosed, priority = prMajor, author = jonas, assignee = ada,
        labels = listOf(lbBug), components = listOf(cmGradle), resolution = rsFixed,
        fixedVersion = ver100, createdDaysAgo = 45, updatedDaysAgo = 30,
    )
    event(issConfigCache, IssueEventKind.STATUS_CHANGED, ada, 30, value = "Closed")
    comment(issConfigCache, ada, "Reworked the task to take its inputs as properties wired at configuration time. The configuration cache is now exercised in CI so this cannot regress silently.", 31)

    val issSourceSetDocs = issue(
        title = "Document the expect/actual layout so contributors know where a platform fix goes",
        description = "Newcomers keep putting a platform fix in the wrong source set, because nothing written down " +
            "explains which of `appleMain`, `nativeMain` and `commonMain` owns what. A page describing the " +
            "hierarchy would save every one of those review round-trips.",
        status = stClosed, priority = prMinor, author = tova, assignee = tova,
        labels = listOf(lbDocs), components = listOf(cmDocs), resolution = rsFixed,
        fixedVersion = ver100, createdDaysAgo = 42, updatedDaysAgo = 29,
    )
    event(issSourceSetDocs, IssueEventKind.STATUS_CHANGED, tova, 29, value = "Closed")
    comment(issSourceSetDocs, sofia, "This is the page I needed three weeks ago. The diagram of the hierarchy in particular.", 28)

    val issAndroidDup = issue(
        title = "Cannot resolve meridian-core on Android",
        description = "Adding the dependency to an Android module fails to resolve. Gradle 8.5, AGP 8.2.",
        status = stClosed, priority = prMajor, author = jonas, assignee = null,
        labels = listOf(lbBug), components = listOf(cmAndroid), resolution = rsDuplicate,
        createdDaysAgo = 40, updatedDaysAgo = 39,
    )
    event(issAndroidDup, IssueEventKind.STATUS_CHANGED, tova, 39, value = "Closed")
    comment(issAndroidDup, tova, "This is the same publication bug as the simulator one — the Android variant was missing from the same list. Closing as a duplicate; the fix is in 0.9.4.", 39)

    val issBlockingApi = issue(
        title = "Add a blocking, non-suspending API for callers who are not in a coroutine",
        description = "Not every caller has a coroutine scope handy, and wrapping every access in `runBlocking` at " +
            "the call site is tedious. A blocking variant of the read and write API would help adoption in older codebases.",
        status = stClosed, priority = prMinor, author = kasper, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmCore), resolution = rsWontFix,
        createdDaysAgo = 38, updatedDaysAgo = 34,
    )
    event(issBlockingApi, IssueEventKind.STATUS_CHANGED, tova, 34, value = "Closed")
    comment(issBlockingApi, tova, "I understand the pull, but no: `runBlocking` does not exist on Kotlin/JS or Wasm, so a blocking API could only be offered on some targets. A multiplatform library that is a different shape per platform is worse than one that asks you to be in a coroutine.", 35)
    comment(issBlockingApi, kasper, "That's fair, and I hadn't thought about the JS side. Withdrawn.", 34)

    val issComposeSample = issue(
        title = "Ship a Compose Multiplatform sample app",
        description = "A sample app in Compose Multiplatform would show the library in a real UI and make the " +
            "Flow-observation story concrete.",
        status = stClosed, priority = prMinor, author = sofia, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmDocs), resolution = rsWontFix,
        createdDaysAgo = 37, updatedDaysAgo = 32,
    )
    event(issComposeSample, IssueEventKind.STATUS_CHANGED, tova, 32, value = "Closed")
    comment(issComposeSample, tova, "Meridian has no opinion about your UI and I would like to keep it that way — a first-party Compose sample reads as an endorsement, and then the SwiftUI people quite reasonably ask why they got nothing. The docs show the Flow and stop there. Community samples very welcome, linked from the README.", 32)

    val issStale = issue(
        title = "Data loss after force-quitting the app",
        description = "Sometimes values written just before the app is force-quit are not there on the next launch. " +
            "Samsung device, Android 13. I don't have a reliable way to reproduce it.",
        status = stClosed, priority = prMajor, author = jonas, assignee = null,
        labels = listOf(lbBug), components = listOf(cmAndroid), resolution = rsStale,
        createdDaysAgo = 60, updatedDaysAgo = 31,
    )
    event(issStale, IssueEventKind.STATUS_CHANGED, tova, 31, value = "Closed")
    comment(issStale, tova, "Asked twice for the device model and whether the writes were awaited. No reply in four weeks, and I can't chase a report I can't reproduce. Closing as stale — please reopen with a reproducer and I will look properly.", 31)

    val issDarwinFsync = issue(
        title = "Writes are not durable on the Apple targets until the store is closed",
        description = "On iOS and macOS a write survives an app restart only if the store was closed cleanly. The " +
            "JVM implementation flushes per commit; the Darwin one is relying on the OS to get around to it.",
        status = stClosed, priority = prBlocker, author = rafael, assignee = rafael,
        labels = listOf(lbBug), components = listOf(cmApple, cmCore), resolution = rsFixed,
        fixedVersion = ver100, createdDaysAgo = 41, updatedDaysAgo = 27,
    )
    event(issDarwinFsync, IssueEventKind.STATUS_CHANGED, rafael, 27, value = "Closed")
    comment(issDarwinFsync, rafael, "Commit now fsyncs on Darwin like it does everywhere else. It costs about 8% on a write-heavy benchmark, which is the correct price for the word \"durable\".", 28)

    val issKotlin23 = issue(
        title = "Deprecation warnings on Kotlin 2.3 in the Gradle plugin",
        description = "Building the plugin against Kotlin 2.3 produces a wall of deprecation warnings for the " +
            "target-configuration APIs. Nothing is broken yet, but the replacements exist and this is what the " +
            "next major will remove.",
        status = stClosed, priority = prMinor, author = ada, assignee = ada,
        labels = listOf(lbBug), components = listOf(cmGradle), resolution = rsFixed,
        fixedVersion = ver110, createdDaysAgo = 26, updatedDaysAgo = 12,
    )
    event(issKotlin23, IssueEventKind.STATUS_CHANGED, ada, 12, value = "Closed")

    // ── In review ─────────────────────────────────────────────────────────────

    val issWasmWasi = issue(
        title = "Add a wasmWasi target",
        description = "A contributed patch adding the wasmWasi target, with the file-backed store implemented " +
            "against the WASI preview-1 filesystem calls. In review; the storage path handling is the part that " +
            "needs the most careful reading.",
        status = stReview, priority = prMinor, author = yara, assignee = wen,
        labels = listOf(lbFeature), components = listOf(cmWeb), plannedVersion = ver110,
        createdDaysAgo = 21, updatedDaysAgo = 2,
    )
    event(issWasmWasi, IssueEventKind.ASSIGNEE_CHANGED, tova, 20, value = "Wen Li")
    comment(issWasmWasi, wen, "Reading through it now. The implementation is good; my only real note is that the path handling assumes a preopened directory at the root, which we should document rather than silently require.", 3)
    comment(issWasmWasi, yara, "> Wen: assumes a preopened directory\n\nGood catch. I'll document it and fail with a clear message instead of a confusing IO error.", 2)

    val issFreezeLeftovers = issue(
        title = "Remove the freeze() leftovers from the Apple source set",
        description = "The Apple implementation still calls `freeze()` in two places and carries a comment about " +
            "the old memory model. It is dead weight under the current model and confuses anyone reading the " +
            "source set for the first time.",
        status = stReview, priority = prMinor, author = rafael, assignee = rafael,
        labels = listOf(lbBug), components = listOf(cmApple), plannedVersion = ver110,
        createdDaysAgo = 18, updatedDaysAgo = 3,
    )
    comment(issFreezeLeftovers, tova, "Please keep the removal in its own commit, so a bisect through the 1.1 window is not confused by an unrelated change riding along.", 3)

    val issBenchHarness = issue(
        title = "Publish the benchmark harness so numbers in issues can be compared",
        description = "Performance claims in issues are currently unfalsifiable, because everyone measures " +
            "differently. Publishing the harness we use internally would let a reporter produce numbers we can " +
            "actually compare against ours.",
        status = stReview, priority = prMinor, author = tova, assignee = tova,
        labels = listOf(lbFeature, lbDocs), components = listOf(cmDocs, cmJvm), plannedVersion = ver110,
        createdDaysAgo = 16, updatedDaysAgo = 4,
    )

    // ── In progress — the 2.0 API rework, as an epic ──────────────────────────

    val issApi2 = issue(
        title = "2.0: a suspend-only public surface",
        description = "The public API grew a mixture of suspending and non-suspending accessors, and which is " +
            "which is now impossible to predict. 2.0 makes the whole surface suspending, deprecates the rest " +
            "through one release, and documents the migration. This is the epic the 2.0 work hangs off.",
        status = stProgress, priority = prMajor, author = tova, assignee = tova,
        labels = listOf(lbBreaking, lbFeature), components = listOf(cmCore), plannedVersion = ver200,
        createdDaysAgo = 24, updatedDaysAgo = 1,
    )
    comment(issApi2, janeway, "As long as 1.x keeps getting fixes while this lands. People on the old API should not feel pushed.", 6)
    comment(issApi2, tova, "> Janeway: 1.x keeps getting fixes\n\nAgreed, and written into the release policy: 1.1.x gets fixes until 2.0 has been out for six months.", 6)

    val issDeprecateBlocking = issue(
        title = "Deprecate the non-suspending accessors with a replacement expression",
        description = "Every non-suspending accessor gets `@Deprecated` with a `ReplaceWith` that actually " +
            "compiles, so the IDE can migrate a call site with one keystroke. Child of the 2.0 epic.",
        status = stProgress, priority = prMajor, author = tova, assignee = wen,
        labels = listOf(lbBreaking), components = listOf(cmCore), plannedVersion = ver200,
        createdDaysAgo = 20, updatedDaysAgo = 1,
    )
    comment(issDeprecateBlocking, wen, "Two thirds done. A handful of the replacements can't be expressed as a one-liner and will need a paragraph in the migration guide instead.", 1)

    val issRenameStore = issue(
        title = "Rename Store to MeridianStore",
        description = "`Store` is a name every second library in a project already has, and importing ours " +
            "shadows theirs. Rename with a typealias left behind for one release. Child of the 2.0 epic.",
        status = stAccepted, priority = prMinor, author = ada, assignee = null,
        labels = listOf(lbBreaking), components = listOf(cmCore), plannedVersion = ver200,
        createdDaysAgo = 19, updatedDaysAgo = 8,
    )

    val issMigrationGuide = issue(
        title = "Write the 1.x → 2.0 migration guide",
        description = "One page: what changed, why, and the mechanical steps for each deprecation, including the " +
            "handful that cannot be automated. Child of the 2.0 epic; it lands with the alpha, not after it.",
        status = stAccepted, priority = prMajor, author = tova, assignee = null,
        labels = listOf(lbDocs, lbBreaking), components = listOf(cmDocs), plannedVersion = ver200,
        createdDaysAgo = 19, updatedDaysAgo = 7,
    )

    val issAtomicMultiKey = issue(
        title = "Atomic multi-key writes",
        description = "Writing several keys as one unit currently means opening a transaction and hoping the " +
            "caller does not forget. A batch API that either applies wholly or not at all is the thing people " +
            "keep reaching for.",
        status = stProgress, priority = prMajor, author = sofia, assignee = tova,
        labels = listOf(lbFeature), components = listOf(cmCore), plannedVersion = ver110,
        createdDaysAgo = 22, updatedDaysAgo = 1,
    )
    comment(issAtomicMultiKey, tova, "Landing in 1.1 rather than waiting for 2.0 — it is additive, so there is no reason to make people wait for the breaking release.", 5)

    val issColdReadRegression = issue(
        title = "Regression: cold reads on 1.1.0-SNAPSHOT are ~40% slower than 1.0.0",
        description = "The nightly benchmark run shows cold-read latency on the JVM target regressing from 1.0.0 " +
            "to the current snapshot. Warm reads are unchanged, which points at the store-open path rather than " +
            "the read path itself. Bisect narrows it to the window in which the index was made lazy.",
        status = stProgress, priority = prBlocker, author = tova, assignee = tova,
        labels = listOf(lbRegression, lbBug), components = listOf(cmCore, cmJvm), plannedVersion = ver110,
        createdDaysAgo = 5, updatedDaysAgo = 1, agentName = "Claude Code",
    )
    comment(
        issColdReadRegression, tova,
        "Bisected across the 1.1 window: the first bad commit is the one that made the index lazy. Cold open now " +
            "pays for a full index build that the eager version amortised at write time. Benchmark numbers for " +
            "each commit are attached to the run in CI.",
        4, agentName = "Claude Code",
    )
    comment(issColdReadRegression, tova, "That matches what I'd expect from that change. Keeping it lazy but building the index incrementally on first read, rather than reverting.", 1)

    // ── Accepted — agreed, waiting for someone to pick up ─────────────────────

    val issLinuxArm = issue(
        title = "Add a linuxArm64 target",
        description = "Requested for CI runners and single-board machines. The native implementation is already " +
            "shared, so this is mostly a publication and a CI job rather than new code.",
        status = stAccepted, priority = prMinor, author = kasper, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmCore), plannedVersion = ver110,
        createdDaysAgo = 17, updatedDaysAgo = 9,
    )
    comment(issLinuxArm, tova, "Happy to take this. The only real work is a runner that can build it — if anyone has an ARM box in CI already, say so.", 9)

    val issKdocPublic = issue(
        title = "Add KDoc to every public declaration in meridian-core",
        description = "About forty public declarations have no documentation at all, so the generated API " +
            "reference has holes in it. Mechanical, self-contained, and a genuinely good first contribution — " +
            "each declaration can be done and reviewed on its own.",
        status = stAccepted, priority = prTrivial, author = tova, assignee = null,
        labels = listOf(lbDocs, lbGoodFirst), components = listOf(cmDocs, cmCore), plannedVersion = ver110,
        createdDaysAgo = 15, updatedDaysAgo = 10,
    )
    comment(issKdocPublic, sofia, "I'd like to take a first slice of this — say the ten declarations in the transaction API — if nobody else has started.", 10)

    val issAndroidStrictMode = issue(
        title = "StrictMode flags a disk read on the main thread during store open",
        description = "Opening a store from the main thread trips StrictMode's disk-read policy on Android. The " +
            "open is genuinely doing IO, so the fix is to move it off the caller's thread rather than to suppress " +
            "the warning.",
        status = stAccepted, priority = prMajor, author = yara, assignee = null,
        labels = listOf(lbBug), components = listOf(cmAndroid), plannedVersion = ver110,
        createdDaysAgo = 14, updatedDaysAgo = 11,
    )

    // ── Needs info — waiting on the reporter ──────────────────────────────────

    val issWindowsBuild = issue(
        title = "Build fails on Windows with a path-too-long error",
        description = "Reported against 1.0.0: the build fails partway through on Windows. The message mentions a " +
            "path length, which we have not been able to reproduce on our own Windows runner.",
        status = stNeedsInfo, priority = prMinor, author = jonas, assignee = null,
        labels = listOf(lbBug), components = listOf(cmGradle), createdDaysAgo = 13, updatedDaysAgo = 6,
    )
    comment(issWindowsBuild, ada, "Could you post the full Gradle output and where the project directory lives? Our runner builds it from a deep path without complaint, so I suspect something specific about the setup rather than the build itself.", 6)

    val issRandomCorruption = issue(
        title = "Store occasionally unreadable after an unclean shutdown",
        description = "Twice in a month the store failed to open after the device lost power, with an error about " +
            "an unrecognised header. Neither store was kept. No reproducer.",
        status = stNeedsInfo, priority = prBlocker, author = kasper, assignee = null,
        labels = listOf(lbBug), components = listOf(cmCore), createdDaysAgo = 11, updatedDaysAgo = 4,
    )
    comment(issRandomCorruption, tova, "This is the report I most want to get to the bottom of, so please keep the file next time it happens — even a corrupt one tells us where the write was interrupted. Raised to Blocker on the strength of the description alone.", 4)
    comment(issRandomCorruption, kasper, "Understood. I've added a step to our support flow to keep a copy before clearing anything.", 4)

    // ── Triage — the inbox ────────────────────────────────────────────────────

    val issEncryption = issue(
        title = "Support encryption at rest",
        description = "Several people have asked for encrypted stores. The interesting question is not the cipher " +
            "but the key: on Apple and Android there is a platform keystore, on the JVM and native there is not, " +
            "and a library that quietly stores a key next to the data it protects has helped nobody.",
        status = stTriage, priority = prMajor, author = sofia, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmCore), createdDaysAgo = 8, updatedDaysAgo = 8,
    )
    comment(issEncryption, tova, "Not saying no. Saying that the key management design has to come first, and that it is a bigger conversation than the encryption itself.", 7)

    val issTriageSweep = issue(
        title = "Nine open reports have had no maintainer reply in over 30 days",
        description = "A sweep of the tracker: nine issues have gone a month or more without a maintainer " +
            "responding, four of them from first-time reporters. Listed oldest first, with a one-line summary of " +
            "what each is waiting on, so they can be worked through in a sitting.",
        status = stTriage, priority = prMinor, author = tova, assignee = null,
        labels = listOf(lbDocs), components = emptyList(), createdDaysAgo = 3, updatedDaysAgo = 3,
        agentName = "Claude Code",
    )
    comment(issTriageSweep, tova, "This is the kind of thing I always mean to do and never do. Taking the four first-timers this afternoon.", 2)

    val issJsIndexedDb = issue(
        title = "Use IndexedDB rather than localStorage on the JS target",
        description = "The browser implementation stores everything in localStorage, which is synchronous, small, " +
            "and string-only. IndexedDB is the right backing store for anything past a few hundred kilobytes.",
        status = stTriage, priority = prMajor, author = wen, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmWeb), createdDaysAgo = 7, updatedDaysAgo = 7,
    )

    val issTypedKeys = issue(
        title = "Typed keys instead of strings",
        description = "A `Key<T>` carrying its value type would turn a class of runtime cast failures into " +
            "compile errors. Almost certainly a 2.0 conversation rather than a 1.x one.",
        status = stTriage, priority = prMinor, author = yara, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmCore), createdDaysAgo = 6, updatedDaysAgo = 6,
    )

    val issReadmeBadge = issue(
        title = "README shows the 0.9.x version badge",
        description = "The badge in the README still points at the 0.9 branch, so the front page tells a first " +
            "visitor to depend on a version that is two releases old.",
        status = stTriage, priority = prTrivial, author = jonas, assignee = null,
        labels = listOf(lbDocs, lbGoodFirst), components = listOf(cmDocs), createdDaysAgo = 2, updatedDaysAgo = 2,
    )

    val issAndroidR8 = issue(
        title = "R8 strips a class needed by reflection in the serializer bridge",
        description = "A release build with R8 full mode throws at the first read, because a class the serializer " +
            "bridge looks up reflectively has been removed. We should ship consumer rules rather than asking " +
            "every app to write them.",
        status = stTriage, priority = prMajor, author = kasper, assignee = null,
        labels = listOf(lbBug), components = listOf(cmAndroid), createdDaysAgo = 1, updatedDaysAgo = 1,
    )

    // ── The 2.0 epic's children ───────────────────────────────────────────────

    fun makeChild(parent: DemoIssue, child: DemoIssue, index: Int) {
        child.parentId = parent.id
        child.childIndex = index.toDouble()
    }
    makeChild(issApi2, issDeprecateBlocking, 0)
    makeChild(issApi2, issRenameStore, 1)
    makeChild(issApi2, issMigrationGuide, 2)

    // ── Notifications ─────────────────────────────────────────────────────────

    fun key(issue: DemoIssue) = "${p.prefix}-${issue.number}"
    w.notifications.add(
        DemoNotification(
            id = w.allocId(),
            kind = NotificationKind.ISSUE_MENTIONED,
            title = "Tova Lindgren mentioned you on ${key(issApi2)}",
            createdAt = hoursAgo(14),
            isRead = false,
            projectId = p.id,
            issueId = issApi2.id,
        ),
    )
    w.notifications.add(
        DemoNotification(
            id = w.allocId(),
            kind = NotificationKind.ISSUE_CREATED,
            title = "Claude Code filed ${key(issColdReadRegression)}",
            createdAt = hoursAgo(2),
            isRead = false,
            projectId = p.id,
            issueId = issColdReadRegression.id,
        ),
    )
}
