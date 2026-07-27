/**
 * The seeded world the demo opens on (LNL-146). Its first and largest project is a
 * team building a Star Trek game for the Commodore Amiga; [seedDemoWorld] then adds
 * two more, each deliberately a *different shape of board*.
 *
 * The three together are the argument that a Lunicle project's vocabulary is its
 * own: this one is planned in sprints and versions, [seedKlinikProject] is a repair
 * shop working a queue with neither, and [seedMeridianProject] is an open-source
 * library whose rhythm is releases. Same product, three boards that share not one
 * status name between them.
 *
 * Everything here is invented but plausible — AGA sprites, copper-list starfields,
 * a 512K memory budget, a ProTracker score, phaser combat — so the board reads like
 * a real one somebody has been working on for weeks. The vocabulary is close to the
 * real Lunicle provisioning default (Very high … Very low, Done / Will not fix /
 * Duplicate, Bug/Feature/Improvement/Codebase) so the board looks authentic; the
 * components and versions are themed to the project, and the status columns drop the
 * default "Backlog" in favour of sprints (see below).
 *
 * The demo user is Captain Kathryn Janeway — instance system administrator and the
 * project's owner. The rest of the crew author, are assigned, and comment, but never
 * sign in.
 *
 * @see DemoWorld
 */
package se.soderbjorn.lunicle.demo

import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.NotificationKind

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/**
 * Build a fresh, fully seeded demo world. Called once on page load.
 *
 * Seeds the Amiga project inline, then hands the world to the other two projects'
 * seeders in turn. They run afterwards rather than first because both make the demo
 * user their owner, and the demo user is established here.
 *
 * @return a world holding three projects, their people, and a notification list
 *   drawn from all three.
 * @see seedKlinikProject
 * @see seedMeridianProject
 */
internal fun seedDemoWorld(): DemoWorld {
    val w = DemoWorld()
    fun daysAgo(n: Int): Long = w.startedAt - n * DAY_MS
    fun hoursAgo(n: Int): Long = w.startedAt - n * HOUR_MS

    // ── Crew ──────────────────────────────────────────────────────────────────

    fun user(name: String, mailbox: String, sysAdmin: Boolean = false): DemoUser {
        val u = DemoUser(w.allocId(), name, "$mailbox@voyager.starfleet", AuthProvider.EMAIL, sysAdmin)
        w.users.add(u)
        return u
    }

    val janeway = user("Kathryn Janeway", "janeway", sysAdmin = true)
    w.demoUserId = janeway.id
    val picard = user("Jean-Luc Picard", "picard")
    val seven = user("Seven of Nine", "seven")
    val data = user("Data", "data")
    val riker = user("William Riker", "riker")
    val laforge = user("Geordi La Forge", "laforge")
    val worf = user("Worf", "worf")
    val troi = user("Deanna Troi", "troi")
    val crusher = user("Beverly Crusher", "crusher")
    val torres = user("B'Elanna Torres", "torres")
    val kim = user("Harry Kim", "kim")
    val doctor = user("The Doctor", "emh")
    val tuvok = user("Tuvok", "tuvok")
    val chakotay = user("Chakotay", "chakotay")

    // ── Project ───────────────────────────────────────────────────────────────

    val p = DemoProject(
        id = w.allocId(),
        name = "Amiga Star Trek",
        prefix = "AST",
        isPublic = true,
        // Forums and private messages are intentionally off in the demo — belt and
        // suspenders with never passing ?forums=1, so both tabs are gone.
        discussionsEnabled = false,
        messagesEnabled = false,
    )
    w.projects.add(p)

    // Statuses — a sprint-oriented set; "Closed" demands a resolution.
    fun status(name: String, pos: Int, req: Boolean = false): DemoStatus {
        val s = DemoStatus(w.allocId(), name, pos, requiresResolution = req)
        p.statuses.add(s)
        return s
    }
    // This board runs on sprints, so it deliberately drops the default "Backlog"
    // status column: unscheduled work is simply the sprint backlog (issues with no
    // sprint), and a second "Backlog" column beside that would say the same thing
    // twice. This is a choice for *this* seeded board only — the real provisioning
    // default (and the demo's own new-project template in provisionProject) keep the
    // full New → Backlog → … set untouched.
    val stTriage = status("Triage", 0)
    val stReady = status("Ready for development", 1)
    val stProgress = status("In progress", 2)
    val stTest = status("Ready for test", 3)
    val stClosed = status("Closed", 4, req = true)

    // Priorities — highest first.
    fun priority(name: String, pos: Int): DemoStatus {
        val s = DemoStatus(w.allocId(), name, pos)
        p.priorities.add(s)
        return s
    }
    val prVeryHigh = priority("Very high", 0)
    val prHigh = priority("High", 1)
    val prNormal = priority("Normal", 2)
    val prLow = priority("Low", 3)
    val prVeryLow = priority("Very low", 4)

    // Resolutions — "Done" is the done one.
    fun resolution(name: String, pos: Int, done: Boolean = false): DemoStatus {
        val s = DemoStatus(w.allocId(), name, pos, isDone = done)
        p.resolutions.add(s)
        return s
    }
    val rsDone = resolution("Done", 0, done = true)
    val rsWontFix = resolution("Will not fix", 1)
    val rsDuplicate = resolution("Duplicate", 2)

    // Labels — the real default.
    fun label(name: String, pos: Int): DemoNamed {
        val v = DemoNamed(w.allocId(), name, pos)
        p.labels.add(v)
        return v
    }
    val lbBug = label("Bug", 0)
    val lbFeature = label("Feature", 1)
    val lbImprovement = label("Improvement", 2)
    val lbCodebase = label("Codebase", 3)

    // Components — themed to the project.
    fun component(name: String, pos: Int): DemoNamed {
        val v = DemoNamed(w.allocId(), name, pos)
        p.components.add(v)
        return v
    }
    val cmGraphics = component("Graphics", 0)
    val cmAudio = component("Audio", 1)
    val cmEngine = component("Engine", 2)
    val cmUI = component("UI", 3)
    val cmTooling = component("Tooling", 4)
    val cmMemory = component("Memory", 5)

    // Versions.
    fun version(name: String, pos: Int): DemoNamed {
        val v = DemoNamed(w.allocId(), name, pos)
        p.versions.add(v)
        return v
    }
    val verProto = version("0.1 — Prototype", 0)
    val verSlice = version("0.5 — Vertical Slice", 1)
    val verGold = version("1.0 — Gold Master", 2)

    // Sprints — two completed, one active.
    fun sprint(name: String, pos: Int, completedAt: Long? = null): DemoSprint {
        val s = DemoSprint(w.allocId(), name, pos, completedAt)
        p.sprints.add(s)
        return s
    }
    val sprint2 = sprint("Sprint 2 — Copper & Starfield", 0, completedAt = daysAgo(28))
    val sprint3 = sprint("Sprint 3 — Audio & Memory", 1, completedAt = daysAgo(14))
    val sprint4 = sprint("Sprint 4 — Bridge & Combat", 2)
    p.activeSprintId = sprint4.id

    // Membership. The demo user owns the board; the rest hold a working set.
    p.members[janeway.id] = mutableSetOf(DemoRoleKeys.PROJECT_OWNER)
    val working = mutableSetOf(
        DemoRoleKeys.CREATE_ISSUE,
        DemoRoleKeys.COMMENT_ON_ISSUE,
        DemoRoleKeys.CHANGE_UNOWNED_ISSUES,
        DemoRoleKeys.BE_ASSIGNED_ISSUE,
    )
    listOf(seven, data, riker, laforge, worf, torres, kim, tuvok, chakotay).forEach {
        p.members[it.id] = working.toMutableSet()
    }
    // A couple of project administrators, and lighter roles for the rest.
    p.members[picard.id] = mutableSetOf(DemoRoleKeys.PROJECT_ADMIN)
    p.members[laforge.id] = mutableSetOf(DemoRoleKeys.PROJECT_ADMIN, DemoRoleKeys.BE_ASSIGNED_ISSUE)
    p.members[troi.id] = mutableSetOf(DemoRoleKeys.COMMENT_ON_ISSUE, DemoRoleKeys.VIEW_PROJECT)
    p.members[crusher.id] = mutableSetOf(DemoRoleKeys.COMMENT_ON_ISSUE, DemoRoleKeys.VIEW_PROJECT)
    p.members[doctor.id] = mutableSetOf(DemoRoleKeys.VIEW_PROJECT)

    // ── Issues ──────────────────────────────────────────────────────────────

    var order = 0.0

    fun issue(
        title: String,
        description: String,
        status: DemoStatus,
        priority: DemoStatus,
        author: DemoUser,
        assignee: DemoUser? = null,
        labels: List<DemoNamed> = emptyList(),
        components: List<DemoNamed> = emptyList(),
        sprint: DemoSprint? = null,
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
            sprintId = sprint?.id,
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
        // Every issue was filed at some point.
        p.events.add(DemoEvent(w.allocId(), issue.id, IssueEventKind.CREATED, authorId = author.id, createdAt = created))
        return issue
    }

    fun comment(issue: DemoIssue, author: DemoUser, body: String, daysAgoValue: Int, agentName: String? = null) {
        p.comments.add(
            DemoComment(w.allocId(), issue.id, body, author.id, agentName, daysAgo(daysAgoValue)),
        )
    }

    fun event(
        issue: DemoIssue,
        kind: IssueEventKind,
        author: DemoUser,
        daysAgoValue: Int,
        value: String? = null,
        values: List<String> = emptyList(),
    ) {
        p.events.add(DemoEvent(w.allocId(), issue.id, kind, value, values, author.id, createdAt = daysAgo(daysAgoValue)))
    }

    // ── Closed (Done, mostly) ─────────────────────────────────────────────────

    val issBoot = issue(
        title = "Boot from floppy into a black screen with 60Hz copper bars",
        description = "Set up the startup-sequence, kill the OS, take over the hardware and prove the copper " +
            "list runs by cycling a few background colours. This is the hello-world the rest of the engine boots on.",
        status = stClosed, priority = prVeryHigh, author = laforge, assignee = laforge,
        labels = listOf(lbCodebase), components = listOf(cmEngine), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 44, updatedDaysAgo = 41,
    )
    event(issBoot, IssueEventKind.STATUS_CHANGED, laforge, 41, value = "Closed")
    comment(issBoot, torres, "Confirmed on a stock A500 with 512K. Bars are rock steady.", 41)

    val issBlitter = issue(
        title = "Blitter clear routine for the 320×256 playfield",
        description = "A blitter-driven clear of the back buffer, so the frame budget starts from zero cost " +
            "instead of a CPU memset. Measure the raster lines it costs.",
        status = stClosed, priority = prHigh, author = torres, assignee = torres,
        labels = listOf(lbCodebase, lbImprovement), components = listOf(cmEngine, cmGraphics),
        sprint = sprint2, resolution = rsDone, fixedVersion = verProto, createdDaysAgo = 40, updatedDaysAgo = 36,
    )
    event(issBlitter, IssueEventKind.STATUS_CHANGED, torres, 36, value = "Closed")
    comment(issBlitter, laforge, "> B'Elanna: costs about 18 raster lines\n\nThat's well inside budget. Nice.", 36)

    val issDoubleBuffer = issue(
        title = "Double-buffer the display so we stop tearing",
        description = "Swap two bitplanes on the vertical blank. Nothing should draw to the visible buffer.",
        status = stClosed, priority = prHigh, author = laforge, assignee = torres,
        labels = listOf(lbBug), components = listOf(cmGraphics), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 38, updatedDaysAgo = 33,
    )
    event(issDoubleBuffer, IssueEventKind.STATUS_CHANGED, torres, 33, value = "Closed")

    val issModPlayer = issue(
        title = "ProTracker MOD replayer on the audio interrupt",
        description = "Wire a 4-channel MOD player to the audio interrupt so the title theme plays without " +
            "the CPU babysitting it. Budget: under 10% frame time.",
        status = stClosed, priority = prNormal, author = kim, assignee = kim,
        labels = listOf(lbFeature), components = listOf(cmAudio), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 26, updatedDaysAgo = 20,
    )
    event(issModPlayer, IssueEventKind.ASSIGNEE_CHANGED, chakotay, 25, value = "Harry Kim")
    event(issModPlayer, IssueEventKind.STATUS_CHANGED, kim, 20, value = "Closed")
    comment(issModPlayer, tuvok, "The replayer is logically flawless. It is also, I note, catchy.", 20)

    val issDup = issue(
        title = "Starfield flickers on every third frame",
        description = "The stars strobe. Looks like a buffer-swap race.",
        status = stClosed, priority = prNormal, author = troi, assignee = torres,
        labels = listOf(lbBug), components = listOf(cmGraphics), resolution = rsDuplicate,
        createdDaysAgo = 22, updatedDaysAgo = 21,
    )
    event(issDup, IssueEventKind.STATUS_CHANGED, torres, 21, value = "Closed")
    comment(issDup, torres, "Duplicate of the copper-list swap timing bug — folding this into that one.", 21)

    val issWontFix = issue(
        title = "Support the 256-colour HAM mode for the viewscreen",
        description = "HAM would give us photographic nebulae, but the fringing on moving sprites is brutal " +
            "and the CPU cost of fixing it up is not worth it for a game that has to hold 25fps.",
        status = stClosed, priority = prLow, author = data, assignee = laforge,
        labels = listOf(lbFeature), components = listOf(cmGraphics), resolution = rsWontFix,
        createdDaysAgo = 30, updatedDaysAgo = 19,
    )
    event(issWontFix, IssueEventKind.STATUS_CHANGED, laforge, 19, value = "Closed")
    comment(issWontFix, data, "The analysis is sound. HAM is the wrong tool for animated sprites over a starfield.", 19)

    // ── Ready for test ────────────────────────────────────────────────────────

    val issWarpStars = issue(
        title = "Warp-stars streak animation when jumping to warp",
        description = "On warp entry, the copper starfield should stretch the stars into streaks toward a " +
            "vanishing point for ~1.5s, then snap back. Drive it entirely off the copper list.",
        status = stTest, priority = prHigh, author = laforge, assignee = laforge,
        labels = listOf(lbFeature), components = listOf(cmGraphics), sprint = sprint4,
        plannedVersion = verSlice, createdDaysAgo = 12, updatedDaysAgo = 3,
    )
    event(issWarpStars, IssueEventKind.STATUS_CHANGED, laforge, 3, value = "Ready for test")
    comment(issWarpStars, riker, "Looks fantastic. One nit: the snap-back is a frame too abrupt.", 3)
    comment(issWarpStars, laforge, "> Will: snap-back too abrupt\n\nEasing it over three frames. Will push a build.", 2)

    val issSaveFloppy = issue(
        title = "Save game to a second floppy",
        description = "Prompt for the save disk, write the ship's state and the current sector, and verify the " +
            "write. Handle a write-protected disk gracefully.",
        status = stTest, priority = prNormal, author = kim, assignee = kim,
        labels = listOf(lbFeature), components = listOf(cmEngine), sprint = sprint4,
        plannedVersion = verSlice, createdDaysAgo = 10, updatedDaysAgo = 4,
    )
    event(issSaveFloppy, IssueEventKind.STATUS_CHANGED, kim, 4, value = "Ready for test")
    comment(issSaveFloppy, tuvok, "I inserted a write-protected disk. It informed me politely. Acceptable.", 4)

    // ── In progress (mostly the active sprint) ────────────────────────────────

    val issPhaserCombat = issue(
        title = "Phaser combat: lock, fire, and register hits",
        description = "The core combat loop. Target lock cycles enemy ships, fire draws a beam from the ship to " +
            "the target, and a hit reduces the target's shields. This is the epic the combat work hangs off.",
        status = stProgress, priority = prVeryHigh, author = janeway, assignee = worf,
        labels = listOf(lbFeature), components = listOf(cmEngine), sprint = sprint4,
        plannedVersion = verSlice, createdDaysAgo = 16, updatedDaysAgo = 1,
    )
    event(issPhaserCombat, IssueEventKind.ASSIGNEE_CHANGED, janeway, 15, value = "Worf")
    comment(issPhaserCombat, worf, "The beam renders. Today it does no damage. Tomorrow it will.", 2)

    val issBridgeUI = issue(
        title = "LCARS bridge UI shell",
        description = "The persistent LCARS frame around the viewscreen — the coloured elbow panels, the status " +
            "readouts and the button strip. An epic; the individual panels are children of this.",
        status = stProgress, priority = prHigh, author = janeway, assignee = data,
        labels = listOf(lbFeature), components = listOf(cmUI), sprint = sprint4,
        plannedVersion = verSlice, createdDaysAgo = 15, updatedDaysAgo = 1,
    )
    comment(issBridgeUI, troi, "The palette feels right. It reads as LCARS at a glance.", 3)

    val issSpriteMux = issue(
        title = "8-sprite multiplexer for more than eight objects on a line",
        description = "The AGA hardware gives us eight sprites; combat needs more ships and torpedoes on screen. " +
            "Reuse sprites down the raster by rewriting their pointers from the copper list.",
        status = stProgress, priority = prHigh, author = torres, assignee = torres,
        labels = listOf(lbImprovement, lbCodebase), components = listOf(cmGraphics, cmEngine), sprint = sprint4,
        plannedVersion = verSlice, createdDaysAgo = 9, updatedDaysAgo = 1,
    )
    comment(issSpriteMux, laforge, "Watch the DMA contention on the left edge — that's where it bit us last time.", 2)

    val issMemoryBudget = issue(
        title = "Keep the whole game inside the 512K chip RAM budget",
        description = "The base A500 has 512K. Bitplanes, sprites, the MOD and the code all share it. Instrument " +
            "the free-chip-RAM figure and put it on the debug overlay so we notice the day we blow the budget.",
        status = stProgress, priority = prVeryHigh, author = seven, assignee = seven,
        labels = listOf(lbImprovement), components = listOf(cmMemory), sprint = sprint4,
        plannedVersion = verSlice, createdDaysAgo = 8, updatedDaysAgo = 1,
        agentName = "Claude Code",
    )
    comment(issMemoryBudget, seven, "We are at 471K of 512K. There is no margin for sentiment.", 1)
    comment(issMemoryBudget, janeway, "> Seven: no margin for sentiment\n\nUnderstood. Let's find the fat.", 1)

    // ── Ready for development ─────────────────────────────────────────────────

    val issTorpedoes = issue(
        title = "Photon torpedo projectiles with a lifetime",
        description = "Torpedoes launch from the ship, travel toward the reticle and expire after a fixed " +
            "distance or on impact. A child of the phaser-combat epic.",
        status = stReady, priority = prHigh, author = worf, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmEngine), sprint = sprint4,
        plannedVersion = verSlice, createdDaysAgo = 7, updatedDaysAgo = 6,
    )
    val issShields = issue(
        title = "Shield energy model and the shield-hit flash",
        description = "Each ship carries a shield value; a hit subtracts from it and flashes the shield bubble. " +
            "At zero, hits reach the hull. Child of the combat epic.",
        status = stReady, priority = prNormal, author = worf, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmEngine), sprint = sprint4,
        plannedVersion = verSlice, createdDaysAgo = 7, updatedDaysAgo = 6,
    )
    val issLcarsPanel = issue(
        title = "LCARS panel frame — the elbow corners",
        description = "The rounded elbow pieces of the LCARS frame, drawn once into a spare bitplane. Child of " +
            "the bridge-UI epic.",
        status = stReady, priority = prNormal, author = data, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmUI), sprint = sprint4,
        createdDaysAgo = 6, updatedDaysAgo = 6,
    )
    val issWarpGauge = issue(
        title = "Warp-core status gauge on the bridge UI",
        description = "An animated bar that rises with warp factor and pulses at maximum warp. Child of the " +
            "bridge-UI epic.",
        status = stReady, priority = prLow, author = data, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmUI), createdDaysAgo = 6, updatedDaysAgo = 6,
    )
    val issRedAlert = issue(
        title = "Red-alert klaxon and screen tint",
        description = "On red alert, loop the klaxon sample and tint the LCARS frame red via a copper palette " +
            "swap. Must not disturb the MOD playing on the other channels.",
        status = stReady, priority = prNormal, author = kim, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmAudio, cmUI), plannedVersion = verSlice,
        createdDaysAgo = 5, updatedDaysAgo = 5,
    )
    comment(issRedAlert, kim, "Three channels for the MOD, one for the klaxon. It'll fit.", 5)

    val issJoystick = issue(
        title = "Read the joystick and debounce the fire button",
        description = "Poll the joystick port each frame, map the eight directions to ship rotation and thrust, " +
            "and debounce fire so one press is one shot.",
        status = stReady, priority = prHigh, author = riker, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmEngine), createdDaysAgo = 5, updatedDaysAgo = 5,
    )

    // ── Backlog ───────────────────────────────────────────────────────────────

    val issTransporter = issue(
        title = "Transporter beam-out effect",
        description = "The sparkle dissolve when a character transports. A vertical wipe with a shimmering " +
            "sprite overlay, timed to a rising sample.",
        status = stTriage, priority = prLow, author = crusher, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmGraphics, cmAudio), createdDaysAgo = 18, updatedDaysAgo = 18,
    )
    val issNebula = issue(
        title = "Scrolling nebula backdrop behind the starfield",
        description = "A slow parallax nebula layer under the stars, dithered to survive 16 colours. Must not " +
            "cost more than a couple of raster lines.",
        status = stTriage, priority = prLow, author = laforge, assignee = null,
        labels = listOf(lbFeature, lbImprovement), components = listOf(cmGraphics), createdDaysAgo = 17, updatedDaysAgo = 17,
    )
    val issDialogue = issue(
        title = "Between-mission dialogue screens",
        description = "Portrait, name plate and a typewriter text crawl for the story beats between missions. " +
            "Text comes from a data file so it can be rewritten without a rebuild.",
        status = stTriage, priority = prNormal, author = troi, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmUI), createdDaysAgo = 15, updatedDaysAgo = 15,
    )
    val issAstDamage = issue(
        title = "Hull-damage decals on the ship sprite",
        description = "As the hull takes damage, swap in progressively scorched sprite frames so the ship looks " +
            "as beaten up as it is.",
        status = stTriage, priority = prLow, author = worf, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmGraphics), createdDaysAgo = 14, updatedDaysAgo = 14,
    )
    val issDiskLoad = issue(
        title = "Trackloader for faster level loads",
        description = "The OS floppy routines are slow. A custom trackloader that reads whole tracks would cut " +
            "level load times dramatically. Risky; needs careful timing.",
        status = stTriage, priority = prNormal, author = torres, assignee = null,
        labels = listOf(lbImprovement, lbCodebase), components = listOf(cmEngine), createdDaysAgo = 13, updatedDaysAgo = 13,
    )
    comment(issDiskLoad, tuvok, "A trackloader is elegant but fragile. I advise thorough testing across drives.", 12)

    val issScore = issue(
        title = "Score and high-score table saved to disk",
        description = "Track a score across a mission and persist a top-ten table to the save disk, with " +
            "three-letter initials entry.",
        status = stTriage, priority = prLow, author = kim, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmEngine, cmUI), createdDaysAgo = 11, updatedDaysAgo = 11,
    )
    val issPalette = issue(
        title = "Unified 16-colour palette across all screens",
        description = "The bridge, the starfield and the dialogue screens each grew their own palette. Reconcile " +
            "them to one shared 16-colour set so a copper swap is never needed just to change screen.",
        status = stTriage, priority = prNormal, author = data, assignee = null,
        labels = listOf(lbImprovement, lbCodebase), components = listOf(cmGraphics), createdDaysAgo = 10, updatedDaysAgo = 10,
    )
    val issBuildTool = issue(
        title = "Asset pipeline: convert IFF art to raw bitplanes at build time",
        description = "Artists work in Deluxe Paint and save IFF; the game wants raw bitplanes. A cross-build " +
            "converter would stop anyone hand-exporting and getting the modulo wrong.",
        status = stTriage, priority = prNormal, author = laforge, assignee = null,
        labels = listOf(lbImprovement), components = listOf(cmTooling), createdDaysAgo = 9, updatedDaysAgo = 9,
    )

    // ── New (untriaged) ───────────────────────────────────────────────────────

    val issCrashOnA1200 = issue(
        title = "Game crashes on boot on an A1200",
        description = "Boots fine on an A500 but hits a grey screen on an A1200. Probably an AGA register we're " +
            "poking assuming OCS. Needs someone with the hardware to narrow it down.",
        status = stTriage, priority = prHigh, author = chakotay, assignee = null,
        labels = listOf(lbBug), components = listOf(cmEngine), createdDaysAgo = 2, updatedDaysAgo = 2,
    )
    val issAudioClick = issue(
        title = "Audible click when the MOD loops",
        description = "There's a faint click at the loop point of the title theme. Might be a DC offset on the " +
            "last sample, might be the loop boundary.",
        status = stTriage, priority = prLow, author = doctor, assignee = null,
        labels = listOf(lbBug), components = listOf(cmAudio), createdDaysAgo = 1, updatedDaysAgo = 1,
    )
    val issFeatureIdea = issue(
        title = "Idea: a two-player split-screen dogfight mode",
        description = "Way out of scope for the slice, but worth capturing: a split-screen versus mode where two " +
            "captains dogfight. Filed for later.",
        status = stTriage, priority = prVeryLow, author = riker, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmEngine), createdDaysAgo = 1, updatedDaysAgo = 1,
    )

    // ── More Sprint 4 work, so the active scope is a full board ───────────────

    // Sprint 4 · Closed (Done) — shipped this sprint.
    val issStarfield = issue(
        title = "Copper-list starfield in three parallax layers",
        description = "Three depths of stars scrolling at different speeds, driven entirely from the copper " +
            "list so the CPU never touches a pixel of it.",
        status = stClosed, priority = prHigh, author = laforge, assignee = laforge,
        labels = listOf(lbFeature), components = listOf(cmGraphics), sprint = sprint4, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 13, updatedDaysAgo = 5,
    )
    event(issStarfield, IssueEventKind.STATUS_CHANGED, laforge, 5, value = "Closed")
    comment(issStarfield, riker, "Depth reads beautifully. It finally feels like space.", 5)

    val issShipSprite = issue(
        title = "Ship rotation with pre-shifted sprite frames",
        description = "32 pre-rotated frames of the hero ship, pre-shifted for the blitter so rotation costs a " +
            "lookup instead of a rotate.",
        status = stClosed, priority = prHigh, author = torres, assignee = torres,
        labels = listOf(lbFeature, lbImprovement), components = listOf(cmGraphics), sprint = sprint4,
        resolution = rsDone, fixedVersion = verSlice, createdDaysAgo = 12, updatedDaysAgo = 6,
    )
    event(issShipSprite, IssueEventKind.STATUS_CHANGED, torres, 6, value = "Closed")

    val issReticle = issue(
        title = "Targeting reticle that snaps to the locked enemy",
        description = "The reticle sprite follows the currently locked target and pulses when a firing solution " +
            "is good.",
        status = stClosed, priority = prNormal, author = worf, assignee = worf,
        labels = listOf(lbFeature), components = listOf(cmUI, cmEngine), sprint = sprint4, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 11, updatedDaysAgo = 4,
    )
    event(issReticle, IssueEventKind.STATUS_CHANGED, worf, 4, value = "Closed")

    val issExplosion = issue(
        title = "Explosion animation when a ship is destroyed",
        description = "A ten-frame blitter animation with a matching sample, played when a ship's hull reaches " +
            "zero.",
        status = stClosed, priority = prNormal, author = laforge, assignee = kim,
        labels = listOf(lbFeature), components = listOf(cmGraphics, cmAudio), sprint = sprint4, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 10, updatedDaysAgo = 5,
    )
    event(issExplosion, IssueEventKind.STATUS_CHANGED, kim, 5, value = "Closed")

    val issThrottle = issue(
        title = "Impulse throttle on the second joystick button",
        description = "Hold the second button to bring the impulse throttle up; release to coast. Tuned so " +
            "combat stays readable.",
        status = stClosed, priority = prNormal, author = riker, assignee = riker,
        labels = listOf(lbFeature), components = listOf(cmEngine), sprint = sprint4, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 9, updatedDaysAgo = 3,
    )
    event(issThrottle, IssueEventKind.STATUS_CHANGED, riker, 3, value = "Closed")

    // Sprint 4 · Ready for test.
    val issShieldRecharge = issue(
        title = "Shields recharge slowly while not taking fire",
        description = "After a few seconds without a hit, shields tick back up toward full. Rate tuned so it " +
            "rewards disengaging without trivialising combat.",
        status = stTest, priority = prNormal, author = worf, assignee = worf,
        labels = listOf(lbFeature), components = listOf(cmEngine), sprint = sprint4, plannedVersion = verSlice,
        createdDaysAgo = 8, updatedDaysAgo = 2,
    )
    event(issShieldRecharge, IssueEventKind.STATUS_CHANGED, worf, 2, value = "Ready for test")

    val issHullReadout = issue(
        title = "Hull-integrity readout on the LCARS strip",
        description = "A segmented hull bar beside the shield gauge, turning amber then red as the hull takes " +
            "damage. A child of the bridge-UI epic.",
        status = stTest, priority = prNormal, author = data, assignee = data,
        labels = listOf(lbFeature), components = listOf(cmUI), sprint = sprint4, plannedVersion = verSlice,
        createdDaysAgo = 7, updatedDaysAgo = 2,
    )
    event(issHullReadout, IssueEventKind.STATUS_CHANGED, data, 2, value = "Ready for test")

    // Sprint 4 · In progress.
    val issEnemyAI = issue(
        title = "Enemy AI: approach, strafe, and break off",
        description = "A simple state machine per enemy ship — close to range, strafe across the player, then " +
            "peel away and come around. Enough to make combat feel alive.",
        status = stProgress, priority = prVeryHigh, author = janeway, assignee = tuvok,
        labels = listOf(lbFeature), components = listOf(cmEngine), sprint = sprint4, plannedVersion = verSlice,
        createdDaysAgo = 9, updatedDaysAgo = 1,
    )
    comment(issEnemyAI, tuvok, "The Kazon shuttle now strafes competently. It is, regrettably, still a Kazon.", 1)

    val issSpawnDirector = issue(
        title = "Wave spawn director for a combat encounter",
        description = "Feeds enemy ships in over time from off-screen so an encounter builds instead of " +
            "appearing all at once.",
        status = stProgress, priority = prHigh, author = riker, assignee = riker,
        labels = listOf(lbFeature), components = listOf(cmEngine), sprint = sprint4, plannedVersion = verSlice,
        createdDaysAgo = 6, updatedDaysAgo = 1,
    )

    val issViewscreen = issue(
        title = "Viewscreen letterbox framing under the LCARS strip",
        description = "Frame the play area as the main viewscreen, with the LCARS strip along the bottom. Child " +
            "of the bridge-UI epic.",
        status = stProgress, priority = prNormal, author = data, assignee = data,
        labels = listOf(lbImprovement), components = listOf(cmUI), sprint = sprint4, createdDaysAgo = 5, updatedDaysAgo = 1,
    )

    // Sprint 4 · Ready for development.
    val issLockTone = issue(
        title = "Target-lock tone when a firing solution is acquired",
        description = "A short rising tone the instant lock is achieved, on the spare audio channel so it does " +
            "not fight the MOD.",
        status = stReady, priority = prNormal, author = kim, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmAudio), sprint = sprint4, createdDaysAgo = 5, updatedDaysAgo = 5,
    )
    val issPauseMenu = issue(
        title = "Pause menu overlay",
        description = "Freeze the game and dim the screen with a small LCARS panel — Resume, Options, Abort " +
            "mission. Must survive being opened mid-combat.",
        status = stReady, priority = prNormal, author = troi, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmUI), sprint = sprint4, createdDaysAgo = 4, updatedDaysAgo = 4,
    )
    val issCloak = issue(
        title = "Cloaking shimmer for the Romulan warbird",
        description = "A copper-driven shimmer that fades the warbird sprite in and out as it cloaks. Should be " +
            "cheap enough to run on several ships at once.",
        status = stReady, priority = prLow, author = worf, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmGraphics), sprint = sprint4, createdDaysAgo = 4, updatedDaysAgo = 4,
    )
    val issDamageFlash = issue(
        title = "Screen shake and red flash when the hull is hit",
        description = "A one-frame red palette flash and a two-pixel screen shake when the hull (not the shield) " +
            "takes a hit, for combat feedback.",
        status = stReady, priority = prNormal, author = riker, assignee = null,
        labels = listOf(lbFeature, lbImprovement), components = listOf(cmGraphics), sprint = sprint4,
        createdDaysAgo = 3, updatedDaysAgo = 3,
    )

    // Sprint 4 · Backlog and New — scheduled but not yet started / just filed.
    val issBorgCube = issue(
        title = "Boss encounter: the Borg cube",
        description = "A large multi-sprite boss that soaks damage and adapts — after a few hits of one type, it " +
            "resists it. The set-piece the slice builds to.",
        status = stTriage, priority = prHigh, author = janeway, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmEngine, cmGraphics), sprint = sprint4, createdDaysAgo = 6, updatedDaysAgo = 6,
    )
    comment(issBorgCube, seven, "Adaptation is straightforward. Resistance is the interesting part.", 5)

    val issDifficulty = issue(
        title = "Per-sector difficulty scaling",
        description = "Scale enemy count and aggression with how deep into the sector map the player is, so the " +
            "back half of the slice actually threatens.",
        status = stTriage, priority = prNormal, author = chakotay, assignee = null,
        labels = listOf(lbImprovement), components = listOf(cmEngine), sprint = sprint4, createdDaysAgo = 5, updatedDaysAgo = 5,
    )
    val issMusicDuck = issue(
        title = "Combat music doesn't duck when the klaxon plays",
        description = "When red alert fires during combat, the klaxon and the MOD both play at full volume and it " +
            "clips. The MOD channels should duck while the klaxon sounds.",
        status = stTriage, priority = prNormal, author = kim, assignee = null,
        labels = listOf(lbBug), components = listOf(cmAudio), sprint = sprint4, createdDaysAgo = 1, updatedDaysAgo = 1,
    )
    val issTorpedoColour = issue(
        title = "Torpedo sprite is the wrong colour on an A1200",
        description = "The photon torpedo comes out magenta on AGA hardware — looks like we're reading a colour " +
            "register that OCS and AGA disagree on.",
        status = stTriage, priority = prNormal, author = chakotay, assignee = null,
        labels = listOf(lbBug), components = listOf(cmGraphics), sprint = sprint4, createdDaysAgo = 1, updatedDaysAgo = 1,
    )

    // ── More backlog and closed work outside the current sprint ──────────────

    val issBriefingMap = issue(
        title = "Mission briefing sector map screen",
        description = "A star map you plot a course across before a mission, with the objective marked and a " +
            "short briefing panel.",
        status = stTriage, priority = prNormal, author = janeway, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmUI), createdDaysAgo = 16, updatedDaysAgo = 16,
    )
    val issPlanetFlyby = issue(
        title = "Planet flyby set-piece between missions",
        description = "A scripted flyby of a planet — a big rotating sprite with a scrolling surface — as a " +
            "breather between combat missions.",
        status = stTriage, priority = prLow, author = troi, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmGraphics), createdDaysAgo = 15, updatedDaysAgo = 15,
    )
    val issDocking = issue(
        title = "Docking-with-starbase sequence",
        description = "An on-rails docking approach that repairs and rearms the ship between missions, with the " +
            "starbase drawn as a large multi-sprite object.",
        status = stTriage, priority = prLow, author = riker, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmGraphics, cmEngine), createdDaysAgo = 14, updatedDaysAgo = 14,
    )
    val issRebind = issue(
        title = "Options: rebindable controls",
        description = "Let the player remap fire, torpedo and throttle across the joystick buttons and a couple " +
            "of keys.",
        status = stTriage, priority = prLow, author = kim, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmUI, cmEngine), createdDaysAgo = 12, updatedDaysAgo = 12,
    )
    val issAttractMode = issue(
        title = "Attract-mode demo loop on the title screen",
        description = "After a while idling on the title, play a canned combat demo, then loop back — the way " +
            "every good arcade-style game did.",
        status = stTriage, priority = prVeryLow, author = data, assignee = null,
        labels = listOf(lbFeature), components = listOf(cmEngine), createdDaysAgo = 11, updatedDaysAgo = 11,
    )
    val issGermanText = issue(
        title = "Localise the on-screen text (German first)",
        description = "All player-facing text already comes from a data file; add a German string table and a " +
            "language toggle. Watch the fixed-width font's umlauts.",
        status = stTriage, priority = prVeryLow, author = chakotay, assignee = null,
        labels = listOf(lbImprovement), components = listOf(cmTooling, cmUI), createdDaysAgo = 10, updatedDaysAgo = 10,
    )
    val issInputLag = issue(
        title = "One frame of input lag on the fire button",
        description = "Fire feels a touch mushy — we're reading the joystick after the game logic instead of " +
            "before it, so a press lands a frame late.",
        status = stTriage, priority = prNormal, author = riker, assignee = null,
        labels = listOf(lbBug), components = listOf(cmEngine), createdDaysAgo = 2, updatedDaysAgo = 2,
    )
    val issTitleTypo = issue(
        title = "Typo on the title screen: \"Stardate\"",
        description = "The stardate line on the title screen reads \"Stardate\" as \"Stardate\" with the wrong " +
            "letters swapped. Trivial, but it's the first thing anyone sees.",
        status = stTriage, priority = prVeryLow, author = doctor, assignee = null,
        labels = listOf(lbBug), components = listOf(cmUI), createdDaysAgo = 1, updatedDaysAgo = 1,
    )

    // Older closed work — the foundations, shipped long before the current sprint.
    val issToolchain = issue(
        title = "Set up the cross-compiler and build toolchain",
        description = "A cross-assembler and linker on the dev machines that produces a bootable ADF, so nobody " +
            "is building on real hardware.",
        status = stClosed, priority = prVeryHigh, author = laforge, assignee = laforge,
        labels = listOf(lbCodebase), components = listOf(cmTooling), resolution = rsDone, fixedVersion = verProto,
        createdDaysAgo = 50, updatedDaysAgo = 48,
    )
    event(issToolchain, IssueEventKind.STATUS_CHANGED, laforge, 48, value = "Closed")

    val issIffLoader = issue(
        title = "IFF loader for the title art",
        description = "Read an IFF ILBM off disk and unpack it into bitplanes at load time, so art stays in a " +
            "format the artists can open.",
        status = stClosed, priority = prNormal, author = data, assignee = data,
        labels = listOf(lbFeature), components = listOf(cmTooling, cmGraphics), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 42, updatedDaysAgo = 39,
    )
    event(issIffLoader, IssueEventKind.STATUS_CHANGED, data, 39, value = "Closed")

    val issVblank = issue(
        title = "Vertical-blank interrupt handler as the frame heartbeat",
        description = "A vertical-blank interrupt that ticks the game clock and does the buffer swap, so the " +
            "whole game runs off the beam instead of a busy-wait.",
        status = stClosed, priority = prHigh, author = torres, assignee = torres,
        labels = listOf(lbCodebase), components = listOf(cmEngine), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 39, updatedDaysAgo = 35,
    )
    event(issVblank, IssueEventKind.STATUS_CHANGED, torres, 35, value = "Closed")

    val issPaletteFade = issue(
        title = "Palette fade-in from black on boot",
        description = "Fade the title screen up from black over half a second by walking the palette registers " +
            "from the copper list.",
        status = stClosed, priority = prLow, author = laforge, assignee = kim,
        labels = listOf(lbImprovement), components = listOf(cmGraphics), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 24, updatedDaysAgo = 22,
    )
    event(issPaletteFade, IssueEventKind.STATUS_CHANGED, kim, 22, value = "Closed")

    val issOldDup = issue(
        title = "Sprite DMA glitch on the far right of the screen",
        description = "Sprites tear at the right edge under heavy DMA load.",
        status = stClosed, priority = prLow, author = kim, assignee = torres,
        labels = listOf(lbBug), components = listOf(cmGraphics), resolution = rsDuplicate, createdDaysAgo = 23, updatedDaysAgo = 22,
    )
    event(issOldDup, IssueEventKind.STATUS_CHANGED, torres, 22, value = "Closed")
    comment(issOldDup, torres, "Same root cause as the sprite-multiplexer DMA contention. Folding it into AST-11.", 22)

    // ── More Sprint 2 · Copper & Starfield (LNL-152) ──────────────────────────
    //
    // The completed sprints started out with only a handful of issues each, so a
    // visitor switching to a finished sprint saw a near-empty board. These fill
    // out Sprint 2 (the copper/starfield foundation) and Sprint 3 (audio/memory)
    // with the rest of the work that plausibly shipped in each — all Closed/Done,
    // fixed against the version that sprint was building toward.

    val issCopperMacros = issue(
        title = "Copper-list assembler macros",
        description = "A small set of macros for building copper lists by hand — WAIT, MOVE and the palette " +
            "helpers — so the rest of the graphics work reads as intent instead of raw copper words.",
        status = stClosed, priority = prHigh, author = laforge, assignee = laforge,
        labels = listOf(lbCodebase), components = listOf(cmEngine, cmGraphics), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 44, updatedDaysAgo = 40,
    )
    event(issCopperMacros, IssueEventKind.STATUS_CHANGED, laforge, 40, value = "Closed")
    comment(issCopperMacros, torres, "These make the copper lists actually readable. Everything after this got easier.", 40)

    val issPlayfieldPalette = issue(
        title = "16-colour playfield palette and register setup",
        description = "Establish the shared 16-colour palette and load the colour registers at boot, so every " +
            "screen starts from a known palette instead of whatever was left in the registers.",
        status = stClosed, priority = prNormal, author = data, assignee = data,
        labels = listOf(lbFeature), components = listOf(cmGraphics), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 43, updatedDaysAgo = 38,
    )
    event(issPlayfieldPalette, IssueEventKind.STATUS_CHANGED, data, 38, value = "Closed")

    val issSpriteDma = issue(
        title = "Hardware sprite DMA channel setup",
        description = "Point the eight sprite DMA channels at their control words and enable sprite DMA, so the " +
            "ship and reticle can ride hardware sprites instead of costing blitter time.",
        status = stClosed, priority = prHigh, author = torres, assignee = torres,
        labels = listOf(lbCodebase), components = listOf(cmGraphics, cmEngine), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 41, updatedDaysAgo = 37,
    )
    event(issSpriteDma, IssueEventKind.STATUS_CHANGED, torres, 37, value = "Closed")

    val issStarfieldScroll = issue(
        title = "Scroll the starfield from the copper, not the CPU",
        description = "Move the per-frame star scroll into the copper list by rewriting the playfield pointers " +
            "each field, so the stars drift with zero CPU cost.",
        status = stClosed, priority = prNormal, author = laforge, assignee = laforge,
        labels = listOf(lbImprovement), components = listOf(cmGraphics), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 40, updatedDaysAgo = 34,
    )
    event(issStarfieldScroll, IssueEventKind.STATUS_CHANGED, laforge, 34, value = "Closed")

    val issBitplaneModulo = issue(
        title = "Bitplane modulo maths for a wider-than-screen buffer",
        description = "Set the bitplane modulos so we can render into a buffer wider than the display and scroll " +
            "into it, which the parallax starfield needs.",
        status = stClosed, priority = prNormal, author = torres, assignee = torres,
        labels = listOf(lbCodebase), components = listOf(cmEngine, cmGraphics), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 39, updatedDaysAgo = 35,
    )
    event(issBitplaneModulo, IssueEventKind.STATUS_CHANGED, torres, 35, value = "Closed")

    val issFreeMouseSprite = issue(
        title = "Disable the mouse-pointer sprite to free a channel",
        description = "The OS leaves sprite 0 driving the mouse pointer. Kill it at boot so all eight sprites are " +
            "ours for the game.",
        status = stClosed, priority = prLow, author = kim, assignee = kim,
        labels = listOf(lbImprovement), components = listOf(cmEngine, cmGraphics), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 38, updatedDaysAgo = 33,
    )
    event(issFreeMouseSprite, IssueEventKind.STATUS_CHANGED, kim, 33, value = "Closed")

    val issRasterBar = issue(
        title = "Raster-time debug bar to measure frame cost",
        description = "Flip a background colour at the start and end of the frame's work so the raster time it " +
            "eats shows as a coloured bar down the screen — a poor engineer's profiler.",
        status = stClosed, priority = prNormal, author = seven, assignee = seven,
        labels = listOf(lbCodebase, lbImprovement), components = listOf(cmTooling, cmEngine), sprint = sprint2,
        resolution = rsDone, fixedVersion = verProto, createdDaysAgo = 40, updatedDaysAgo = 32,
    )
    event(issRasterBar, IssueEventKind.STATUS_CHANGED, seven, 32, value = "Closed")
    comment(issRasterBar, seven, "The bar does not lie. It has already told me three unwelcome things.", 32)

    val issLogoCycle = issue(
        title = "Colour-cycle the title logo from the copper list",
        description = "Walk a couple of palette registers under the title logo each frame so it shimmers, driven " +
            "entirely from the copper so it costs nothing.",
        status = stClosed, priority = prLow, author = data, assignee = kim,
        labels = listOf(lbFeature), components = listOf(cmGraphics), sprint = sprint2, resolution = rsDone,
        fixedVersion = verProto, createdDaysAgo = 37, updatedDaysAgo = 30,
    )
    event(issLogoCycle, IssueEventKind.STATUS_CHANGED, kim, 30, value = "Closed")

    // ── More Sprint 3 · Audio & Memory (LNL-152) ──────────────────────────────

    val issChipAllocator = issue(
        title = "Chip-RAM allocator with a high-water mark",
        description = "A tiny bump allocator for chip RAM that records the peak it ever hands out, so we can see " +
            "how close we run to the 512K ceiling across a session.",
        status = stClosed, priority = prVeryHigh, author = seven, assignee = seven,
        labels = listOf(lbCodebase), components = listOf(cmMemory), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 30, updatedDaysAgo = 23,
    )
    event(issChipAllocator, IssueEventKind.STATUS_CHANGED, seven, 23, value = "Closed")
    comment(issChipAllocator, seven, "Every byte is now accounted for. This is the only acceptable state.", 23)

    val issFreeFadeBuffers = issue(
        title = "Free the boot palette-fade buffers after the intro",
        description = "The fade-in scratch buffers are dead weight once the title is up. Hand them back to the " +
            "allocator so the game proper starts with the RAM it needs.",
        status = stClosed, priority = prNormal, author = seven, assignee = seven,
        labels = listOf(lbImprovement), components = listOf(cmMemory), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 28, updatedDaysAgo = 21,
    )
    event(issFreeFadeBuffers, IssueEventKind.STATUS_CHANGED, seven, 21, value = "Closed")

    val issSampleBank = issue(
        title = "Sample bank: load sound effects into chip RAM once",
        description = "Load every sound effect off disk into a single chip-RAM bank at level start, so playing a " +
            "sample is just pointing the audio channel at it — no per-shot disk access.",
        status = stClosed, priority = prHigh, author = kim, assignee = kim,
        labels = listOf(lbFeature), components = listOf(cmAudio, cmMemory), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 27, updatedDaysAgo = 20,
    )
    event(issSampleBank, IssueEventKind.STATUS_CHANGED, kim, 20, value = "Closed")

    val issOneShotSamples = issue(
        title = "One-shot sample playback on the spare audio channel",
        description = "Fire a sound effect on the one channel the MOD leaves free, stopping it cleanly when it " +
            "finishes so it never loops or fights the music.",
        status = stClosed, priority = prNormal, author = kim, assignee = kim,
        labels = listOf(lbFeature), components = listOf(cmAudio), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 26, updatedDaysAgo = 19,
    )
    event(issOneShotSamples, IssueEventKind.STATUS_CHANGED, kim, 19, value = "Closed")

    val issPackSprites = issue(
        title = "Pack the pre-shifted sprite frames to reclaim chip RAM",
        description = "The 32 pre-shifted ship frames were laid out with generous padding. Pack them tightly to " +
            "win back several kilobytes of chip RAM for the sample bank.",
        status = stClosed, priority = prNormal, author = torres, assignee = torres,
        labels = listOf(lbImprovement), components = listOf(cmMemory, cmGraphics), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 27, updatedDaysAgo = 20,
    )
    event(issPackSprites, IssueEventKind.STATUS_CHANGED, torres, 20, value = "Closed")
    comment(issPackSprites, seven, "Four kilobytes recovered. I have already allocated them elsewhere.", 20)

    val issMasterVolume = issue(
        title = "Master volume ramp so audio fades with the palette",
        description = "Ramp all four channels' volume together so the title's audio fades up with the palette " +
            "fade-in and down on a screen transition, instead of cutting in and out.",
        status = stClosed, priority = prLow, author = kim, assignee = kim,
        labels = listOf(lbImprovement), components = listOf(cmAudio), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 25, updatedDaysAgo = 18,
    )
    event(issMasterVolume, IssueEventKind.STATUS_CHANGED, kim, 18, value = "Closed")

    val issChannelArbiter = issue(
        title = "Audio channel arbitration between the MOD and effects",
        description = "A small arbiter that lends the MOD's fourth channel to a sound effect and hands it back " +
            "when the effect ends, so effects and music share four channels without stepping on each other.",
        status = stClosed, priority = prNormal, author = kim, assignee = kim,
        labels = listOf(lbCodebase), components = listOf(cmAudio), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 26, updatedDaysAgo = 18,
    )
    event(issChannelArbiter, IssueEventKind.STATUS_CHANGED, kim, 18, value = "Closed")
    comment(issChannelArbiter, tuvok, "The arbitration logic is sound. Music and phasers now coexist. Remarkable.", 18)

    val issCopperPool = issue(
        title = "Move the copper lists into a tighter memory pool",
        description = "The copper lists were scattered across chip RAM as they grew. Gather them into one " +
            "contiguous pool so the double-buffered lists sit together and the layout is predictable.",
        status = stClosed, priority = prNormal, author = seven, assignee = seven,
        labels = listOf(lbCodebase, lbImprovement), components = listOf(cmMemory, cmGraphics), sprint = sprint3,
        resolution = rsDone, fixedVersion = verSlice, createdDaysAgo = 24, updatedDaysAgo = 17,
    )
    event(issCopperPool, IssueEventKind.STATUS_CHANGED, seven, 17, value = "Closed")

    val issStripSymbols = issue(
        title = "Strip debug symbols from the release ADF to save RAM",
        description = "The debug build's symbol table and the raster-bar profiler pad the binary. Build a lean " +
            "release ADF with them compiled out so the shipped game keeps the RAM for itself.",
        status = stClosed, priority = prLow, author = laforge, assignee = laforge,
        labels = listOf(lbImprovement), components = listOf(cmTooling, cmMemory), sprint = sprint3, resolution = rsDone,
        fixedVersion = verSlice, createdDaysAgo = 23, updatedDaysAgo = 16,
    )
    event(issStripSymbols, IssueEventKind.STATUS_CHANGED, laforge, 16, value = "Closed")

    // ── Epics (LNL-55): parent the children, in work order ───────────────────

    fun makeChild(parent: DemoIssue, child: DemoIssue, index: Int) {
        child.parentId = parent.id
        child.childIndex = index.toDouble()
    }
    makeChild(issPhaserCombat, issTorpedoes, 0)
    makeChild(issPhaserCombat, issShields, 1)
    makeChild(issPhaserCombat, issShieldRecharge, 2)
    makeChild(issPhaserCombat, issDamageFlash, 3)
    makeChild(issBridgeUI, issLcarsPanel, 0)
    makeChild(issBridgeUI, issWarpGauge, 1)
    makeChild(issBridgeUI, issHullReadout, 2)
    makeChild(issBridgeUI, issViewscreen, 3)

    // ── Notifications — a handful unread so the bell pulses on load ───────────

    fun notify(
        kind: NotificationKind,
        title: String,
        issue: DemoIssue,
        hoursAgoValue: Int,
        read: Boolean = false,
    ) {
        w.notifications.add(
            DemoNotification(
                id = w.allocId(),
                kind = kind,
                title = title,
                createdAt = hoursAgo(hoursAgoValue),
                isRead = read,
                projectId = p.id,
                issueId = issue.id,
            ),
        )
    }
    fun key(issue: DemoIssue) = "${p.prefix}-${issue.number}"
    notify(NotificationKind.ISSUE_ASSIGNED, "Janeway assigned ${key(issPhaserCombat)} to Worf", issPhaserCombat, 30, read = true)
    notify(NotificationKind.ISSUE_MENTIONED, "Seven of Nine mentioned you on ${key(issMemoryBudget)}", issMemoryBudget, 20)
    notify(NotificationKind.ISSUE_UPDATED, "Geordi La Forge moved ${key(issWarpStars)} to Ready for test", issWarpStars, 6)
    notify(NotificationKind.ISSUE_CREATED, "Chakotay filed ${key(issCrashOnA1200)}", issCrashOnA1200, 4)
    notify(NotificationKind.ISSUE_MENTIONED, "William Riker mentioned you on ${key(issWarpStars)}", issWarpStars, 3)

    // ── The other two projects ───────────────────────────────────────────────
    //
    // Each brings its own people and its own notifications, so the bell speaks for
    // the whole instance rather than for this board alone.

    seedKlinikProject(w)
    seedMeridianProject(w)

    return w
}
