/**
 * The second seeded project the demo opens with: **Kilobyte Klinik**, a workshop
 * that repairs vintage home computers.
 *
 * This board exists to prove a point the Amiga board cannot make on its own —
 * that a Lunicle project's vocabulary is entirely its own. Nothing here is a
 * sprint, a version or a story point. An issue is one machine on a bench, and the
 * columns are the stations it passes through: intake, diagnosis, waiting for a
 * part, waiting for the customer to approve a quote, and finally collected.
 *
 * Concretely, against [seedDemoWorld]'s Amiga project this board:
 *  - uses seven statuses rather than five, three of which are kinds of *waiting*;
 *  - names its priorities after shop urgency (Rush … Shelf project) rather than
 *    Very high … Very low, so a visitor sees the priority set really is per-project;
 *  - resolves to shop outcomes — repaired, beyond economical repair, quote declined;
 *  - files every job under the **platform** it is, so the component filter doubles
 *    as "show me the Commodore queue";
 *  - has **no sprints and no versions at all**, which is the whole contrast: one
 *    project using every axis, one using almost none;
 *  - **estimates nothing** (LNL-215), for the same reason. A bench quotes a price, not
 *    a number of days, and half this queue is waiting on a part or on a customer rather
 *    than on anybody's hands. So the board stays on `EstimateMode.NONE` — the default,
 *    and the state most real projects stay in forever — which means no estimate cell, no
 *    popover and no read-mode line anywhere on it. That the feature can be *entirely
 *    absent* is worth seeing beside two boards where it is present;
 *  - turns on `requireComponent`, because a job that does not say which machine it
 *    is has not been booked in properly.
 *
 * It does carry the three relation kinds every project is created with (LNL-215),
 * because a workshop relates jobs to each other as readily as a development team does —
 * two machines from one collector with the same fault, or a job waiting on a donor
 * that is itself still in a crate somewhere.
 *
 * The faults are real ones these machines actually suffer: leaking Varta barrel
 * batteries on the A600 and the A501 trapdoor, the Macintosh SE's chirp, the
 * SE/30's simasimac, perished tape belts in a CPC 464, 4116 lower-RAM failures in
 * a rubber-key Spectrum.
 *
 * The shop's crew is its own — none of the Amiga project's people work here. The
 * demo user (Captain Janeway) owns the board because she owns the instance; she
 * appears as the proprietor and stays out of the technical arguments.
 *
 * @see seedDemoWorld
 * @see seedMeridianProject
 * @see DemoWorld
 */
package se.soderbjorn.lunicle.demo

import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.NotificationKind

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/**
 * Seed the repair-shop project into an existing world.
 *
 * Called by [seedDemoWorld] after the Amiga project has been built, so the demo
 * user already exists and can be made this board's owner too.
 *
 * @param w the world being seeded; its user list, project list and notifications
 *   are all appended to.
 * @see seedDemoWorld
 */
internal fun seedKlinikProject(w: DemoWorld) {
    fun daysAgo(n: Int): Long = w.startedAt - n * DAY_MS
    fun hoursAgo(n: Int): Long = w.startedAt - n * HOUR_MS

    // ── The crew ──────────────────────────────────────────────────────────────
    //
    // A small shop: a proprietor, a bench full of specialists, a front desk and an
    // apprentice who may look and comment but not touch the queue.

    fun user(name: String, mailbox: String): DemoUser {
        val u = DemoUser(w.allocId(), name, "$mailbox@kilobyteklinik.se", AuthProvider.EMAIL)
        w.users.add(u)
        return u
    }

    val janeway = w.demoUser
    val nadia = user("Nadia Ferreira", "nadia")
    val otto = user("Otto Lindqvist", "otto")
    val priya = user("Priya Raman", "priya")
    val sam = user("Sam Okonkwo", "sam")
    val elin = user("Elin Nordh", "elin")
    val marcus = user("Marcus Kwan", "marcus")
    val hedvig = user("Hedvig Ström", "hedvig")
    val milo = user("Milo Åkerberg", "milo")

    // ── The project ───────────────────────────────────────────────────────────

    val p = DemoProject(
        id = w.allocId(),
        name = "Kilobyte Klinik",
        prefix = "FIX",
        // Same as the Amiga board: forums and private messages stay off in the demo.
        discussionsEnabled = false,
        messagesEnabled = false,
        // A job must say which machine it is before it can be booked in.
        requireComponent = true,
        // A shop cares who took the machine in, so the author rides on the card.
        showIssueAuthor = true,
    )
    w.projects.add(p)

    // Statuses — the stations a machine passes through. Three of them are kinds of
    // waiting, which is what makes this board read as a queue rather than a plan.
    fun status(name: String, pos: Int, req: Boolean = false): DemoStatus {
        val s = DemoStatus(w.allocId(), name, pos, requiresResolution = req)
        p.statuses.add(s)
        return s
    }
    val stIntake = status("Intake", 0)
    val stDiagnosis = status("Diagnosis", 1)
    val stParts = status("Awaiting parts", 2)
    val stBench = status("On the bench", 3)
    val stApproval = status("Awaiting customer approval", 4)
    val stPickup = status("Ready for pickup", 5)
    val stClosed = status("Closed", 6, req = true)

    // Priorities — shop urgency, deliberately not the Very high … Very low default.
    fun priority(name: String, pos: Int): DemoStatus {
        val s = DemoStatus(w.allocId(), name, pos)
        p.priorities.add(s)
        return s
    }
    val prRush = priority("Rush", 0)
    val prStandard = priority("Standard", 1)
    val prWhenItFits = priority("When it fits", 2)
    val prShelf = priority("Shelf project", 3)

    // Resolutions — how a job ends. Two of them count as done: a machine repaired
    // with a substitute part still went home working.
    fun resolution(name: String, pos: Int, done: Boolean = false): DemoStatus {
        val s = DemoStatus(w.allocId(), name, pos, isDone = done)
        p.resolutions.add(s)
        return s
    }
    val rsRepaired = resolution("Repaired", 0, done = true)
    val rsSubstitute = resolution("Repaired with substitute parts", 1, done = true)
    val rsNoFault = resolution("No fault found", 2)
    val rsUneconomical = resolution("Beyond economical repair", 3)
    val rsDeclined = resolution("Customer declined quote", 4)
    val rsUnrepaired = resolution("Returned unrepaired", 5)

    // Labels — what kind of work the job turned out to be.
    fun label(name: String, pos: Int): DemoNamed {
        val v = DemoNamed(w.allocId(), name, pos)
        p.labels.add(v)
        return v
    }
    val lbRecap = label("Recap", 0)
    val lbBattery = label("Battery damage", 1)
    val lbChip = label("Chip failure", 2)
    val lbDrive = label("Drive alignment", 3)
    val lbRecovery = label("Data recovery", 4)
    val lbCosmetic = label("Cosmetic", 5)
    val lbRework = label("Rework", 6)

    // Components — the platform. Filtering by component gives you the Commodore
    // queue, the Atari queue, and so on, which is how a bench actually thinks.
    fun component(name: String, pos: Int): DemoNamed {
        val v = DemoNamed(w.allocId(), name, pos)
        p.components.add(v)
        return v
    }
    val cmCommodore = component("Commodore 8-bit", 0)
    val cmAmiga = component("Amiga", 1)
    val cmAtari = component("Atari", 2)
    val cmApple = component("Apple & Macintosh", 3)
    val cmSinclair = component("Sinclair", 4)
    val cmAmstrad = component("Amstrad & MSX", 5)
    val cmPeripherals = component("Peripherals & drives", 6)

    // No sprints and no versions. A workshop has neither, and leaving both empty is
    // the point of this board — see the file preamble. `estimateMode` is left at its
    // default, `none`, for the same reason and is deliberately not stated above: the
    // default is exactly what is being demonstrated, and writing it out would suggest
    // somebody chose it.

    // Relation kinds, though — a shop does relate jobs to one another (LNL-215). Seeded
    // by the same helper `provisionProject` uses, so this board's vocabulary cannot drift
    // from a board a visitor creates.
    val (rkBlockedBy, _, rkRelatedTo) = seedDefaultRelationKinds(w, p)

    // Membership. Janeway owns it; Nadia runs the bench day to day; Otto is on the
    // front desk and books jobs in; Milo is the apprentice and may only look and talk.
    p.members[janeway.id] = DemoRungKeys.OWNER
    listOf(priya, sam, elin, marcus, hedvig).forEach { p.members[it.id] = DemoRungKeys.MAINTAINER }
    p.members[nadia.id] = DemoRungKeys.ADMIN
    p.members[otto.id] = DemoRungKeys.MAINTAINER
    p.members[milo.id] = DemoRungKeys.CONTRIBUTOR

    // ── Jobs ──────────────────────────────────────────────────────────────────
    //
    // No sprint or version parameters here, unlike the Amiga board's builder: this
    // project has neither, and a helper that cannot take them says so plainly.

    var order = 0.0

    fun job(
        title: String,
        description: String,
        status: DemoStatus,
        priority: DemoStatus,
        author: DemoUser,
        assignee: DemoUser? = null,
        labels: List<DemoNamed> = emptyList(),
        components: List<DemoNamed>,
        resolution: DemoStatus? = null,
        createdDaysAgo: Int,
        updatedDaysAgo: Int = createdDaysAgo,
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
            assigneeId = assignee?.id,
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

    fun comment(issue: DemoIssue, author: DemoUser, body: String, daysAgoValue: Int) {
        p.comments.add(DemoComment(w.allocId(), issue.id, body, author.id, null, daysAgo(daysAgoValue)))
    }

    fun event(issue: DemoIssue, kind: IssueEventKind, author: DemoUser, daysAgoValue: Int, value: String? = null) {
        p.events.add(DemoEvent(w.allocId(), issue.id, kind, value, emptyList(), author.id, createdAt = daysAgo(daysAgoValue)))
    }

    /**
     * Link two jobs, and record it on both (LNL-215).
     *
     * The same helper the Amiga board has, for the same reasons — one stored row, two
     * history events, each carrying its own side's word. [from] is the side the kind's
     * own name describes and, for a blocking kind, the side that dims.
     */
    fun relate(from: DemoIssue, kind: DemoRelationKind, to: DemoIssue, author: DemoUser, daysAgoValue: Int) {
        p.relations.add(DemoRelation(w.allocId(), from.id, to.id, kind.id))
        p.events.add(
            DemoEvent(
                w.allocId(), from.id, IssueEventKind.RELATION_ADDED, "${p.prefix}-${to.number}",
                authorId = author.id, createdAt = daysAgo(daysAgoValue),
                relationKind = kind.labelFor(isFromSide = true),
            ),
        )
        p.events.add(
            DemoEvent(
                w.allocId(), to.id, IssueEventKind.RELATION_ADDED, "${p.prefix}-${from.number}",
                authorId = author.id, createdAt = daysAgo(daysAgoValue),
                relationKind = kind.labelFor(isFromSide = false),
            ),
        )
    }

    // ── Closed — collected, or ended some other way ───────────────────────────

    val jobBreadbin = job(
        title = "C64 breadbin — black screen, PLA failure",
        description = "Powers up, no picture, no border. The 906114-01 PLA runs hot enough to be uncomfortable " +
            "within a minute. Socket it and fit a modern replacement, then soak-test for a full day before it goes back.",
        status = stClosed, priority = prStandard, author = otto, assignee = elin,
        labels = listOf(lbChip), components = listOf(cmCommodore), resolution = rsSubstitute,
        createdDaysAgo = 34, updatedDaysAgo = 26,
    )
    event(jobBreadbin, IssueEventKind.STATUS_CHANGED, elin, 26, value = "Closed")
    comment(jobBreadbin, elin, "Original PLA was cooking. Socketed and fitted a replacement — 24 hours on soak, no resets.", 27)
    comment(jobBreadbin, otto, "Customer collected. Warned him the replacement is not the original part; he was delighted rather than upset.", 26)

    val jobA501 = job(
        title = "Amiga 500 — Varta leak on the A501 trapdoor, corrosion under the RAM",
        description = "The trapdoor expansion's barrel battery has vented over the board. Green crawl reaches two " +
            "of the RAM chips and the clock circuit. Remove the battery, neutralise, and see what is left of the traces.",
        status = stClosed, priority = prRush, author = otto, assignee = sam,
        labels = listOf(lbBattery, lbChip), components = listOf(cmAmiga), resolution = rsRepaired,
        createdDaysAgo = 31, updatedDaysAgo = 22,
    )
    event(jobA501, IssueEventKind.ASSIGNEE_CHANGED, nadia, 30, value = "Sam Okonkwo")
    event(jobA501, IssueEventKind.STATUS_CHANGED, sam, 22, value = "Closed")
    comment(jobA501, sam, "Battery out, board neutralised and washed. Four traces gone; ran wire links. Clock keeps time again.", 24)
    comment(jobA501, nadia, "> Sam: four traces gone\n\nPhotograph the links before you close it up. If it comes back in five years we'll want to know what we did.", 23)

    val jobSeChirp = job(
        title = "Macintosh SE — chirps and will not boot: analogue board recap",
        description = "The classic chirp-of-death. No chime, no video, just a repeating tick from the analogue " +
            "board as the supply tries to start and folds back. Full recap of the analogue board.",
        status = stClosed, priority = prStandard, author = otto, assignee = marcus,
        labels = listOf(lbRecap), components = listOf(cmApple), resolution = rsRepaired,
        createdDaysAgo = 29, updatedDaysAgo = 20,
    )
    event(jobSeChirp, IssueEventKind.STATUS_CHANGED, marcus, 20, value = "Closed")
    comment(jobSeChirp, marcus, "Every electrolytic on the analogue board replaced. Chimes first time. Flyback is fine, which was the thing I was worried about.", 21)

    val job1541 = job(
        title = "1541 — head alignment; customer's only copy of her thesis is on the disk",
        description = "Drive reads its own writes and nothing else, which is the usual sign of a drifted head. The " +
            "customer has one disk from 1987 with a thesis on it and no backup. Align, then image the disk before anything else.",
        status = stClosed, priority = prRush, author = otto, assignee = hedvig,
        labels = listOf(lbDrive, lbRecovery), components = listOf(cmPeripherals), resolution = rsRepaired,
        createdDaysAgo = 27, updatedDaysAgo = 18,
    )
    event(job1541, IssueEventKind.STATUS_CHANGED, hedvig, 18, value = "Closed")
    comment(job1541, hedvig, "Imaged the disk before touching the alignment — read clean on the third pass. Thesis recovered in full, sent to her as a d64 and as text.", 19)
    comment(job1541, otto, "She cried a bit at the desk. Best morning we've had in a while.", 18)

    val jobSpectrum = job(
        title = "ZX Spectrum 48K — garbage screen from cold, lower RAM failure",
        description = "Rubber key model. Boots to a screen of noise; the pattern points at the lower 16K, which " +
            "on this machine means one of the 4116s. Piggyback to find the culprit, then socket it.",
        status = stClosed, priority = prStandard, author = otto, assignee = priya,
        labels = listOf(lbChip), components = listOf(cmSinclair), resolution = rsRepaired,
        createdDaysAgo = 25, updatedDaysAgo = 17,
    )
    event(jobSpectrum, IssueEventKind.STATUS_CHANGED, priya, 17, value = "Closed")
    comment(jobSpectrum, priya, "IC6 was the bad one. Socketed while I was in there, so the next one is a two-minute job rather than an hour.", 18)

    val job800xl = job(
        title = "Atari 800XL — dead, no video at all",
        description = "No output on the RF or the monitor port, no life on reset. Suspect the memory controller " +
            "rather than the CPU given there is nothing on screen at all.",
        status = stClosed, priority = prStandard, author = otto, assignee = elin,
        labels = listOf(lbChip), components = listOf(cmAtari), resolution = rsRepaired,
        createdDaysAgo = 24, updatedDaysAgo = 15,
    )
    event(job800xl, IssueEventKind.STATUS_CHANGED, elin, 15, value = "Closed")
    comment(job800xl, elin, "Failed FREDDIE. Replaced from the parts machine on the top shelf. Boots to BASIC.", 16)

    val jobCpc464 = job(
        title = "Amstrad CPC 464 — tape motor turns but nothing loads",
        description = "The belt in the built-in datacorder has gone to tar, as they all do. Strip the mechanism, " +
            "clean off the residue, fit a new belt and check the speed against a reference tape.",
        status = stClosed, priority = prWhenItFits, author = otto, assignee = priya,
        labels = listOf(lbDrive), components = listOf(cmAmstrad), resolution = rsRepaired,
        createdDaysAgo = 23, updatedDaysAgo = 14,
    )
    event(jobCpc464, IssueEventKind.STATUS_CHANGED, priya, 14, value = "Closed")
    comment(jobCpc464, priya, "Belt was liquid. New belt, azimuth tweaked against the reference tape, loads first time every time now.", 15)

    val job1084 = job(
        title = "Commodore 1084S — picture shrunk to a letterbox and a rising whine",
        description = "Image has collapsed vertically and there is an audible whine from the chassis that gets " +
            "worse as it warms. Almost certainly dried-out capacitors in the supply and the vertical stage.",
        status = stClosed, priority = prStandard, author = otto, assignee = nadia,
        labels = listOf(lbRecap), components = listOf(cmPeripherals), resolution = rsRepaired,
        createdDaysAgo = 22, updatedDaysAgo = 13,
    )
    event(job1084, IssueEventKind.STATUS_CHANGED, nadia, 13, value = "Closed")
    comment(job1084, nadia, "Recapped the supply and the vertical section. Geometry back to full height and the whine is gone. Discharged the tube properly, Milo — come and watch next time.", 14)

    val jobMacPlus = job(
        title = "Macintosh Plus — external SCSI drive is never seen",
        description = "Machine boots happily from floppy but nothing on the SCSI chain shows up, with a known-good " +
            "drive and a known-good cable. Termination has been checked at the customer's end.",
        status = stClosed, priority = prStandard, author = otto, assignee = marcus,
        labels = listOf(lbChip), components = listOf(cmApple), resolution = rsRepaired,
        createdDaysAgo = 21, updatedDaysAgo = 12,
    )
    event(jobMacPlus, IssueEventKind.STATUS_CHANGED, marcus, 12, value = "Closed")
    comment(jobMacPlus, marcus, "Dead SCSI controller. Replaced it and the drive appeared immediately.", 13)

    val jobA600 = job(
        title = "Amiga 600 — battery damage past the point of rescue",
        description = "The barrel battery has been leaking for years, unnoticed, in a loft. The corrosion has gone " +
            "under the custom chips and eaten the inner layers. Assess honestly rather than optimistically.",
        status = stClosed, priority = prStandard, author = otto, assignee = sam,
        labels = listOf(lbBattery), components = listOf(cmAmiga), resolution = rsUneconomical,
        createdDaysAgo = 20, updatedDaysAgo = 11,
    )
    event(jobA600, IssueEventKind.STATUS_CHANGED, sam, 11, value = "Closed")
    comment(jobA600, sam, "I spent two hours on it and stopped. The damage is in the inner layers under Gayle — rebuilding it would cost more than a working A600 and I could not promise it would hold.", 12)
    comment(jobA600, janeway, "> Sam: could not promise it would hold\n\nThen it's the right call. Return it with the photographs and don't charge for the assessment.", 11)

    val job1040stf = job(
        title = "Atari 1040STF — no video, Shifter failure; quote declined",
        description = "Monochrome and colour both dead, machine otherwise alive. Shifter has failed. Parts are " +
            "getting scarce and the labour is not trivial, so quote before proceeding.",
        status = stClosed, priority = prStandard, author = otto, assignee = elin,
        labels = listOf(lbChip), components = listOf(cmAtari), resolution = rsDeclined,
        createdDaysAgo = 19, updatedDaysAgo = 10,
    )
    event(job1040stf, IssueEventKind.STATUS_CHANGED, otto, 10, value = "Closed")
    comment(job1040stf, otto, "Quoted for the Shifter and the labour. Customer thought about it for a week and decided to keep it as a shelf ornament. Returned untouched, no charge.", 10)

    val jobVic20 = job(
        title = "VIC-20 — reported random resets, nothing found on the bench",
        description = "Customer reports resets every few minutes at home. Run it on the soak bench for 48 hours " +
            "with a scope on the supply rails and see whether it ever does it here.",
        status = stClosed, priority = prWhenItFits, author = otto, assignee = elin,
        components = listOf(cmCommodore), resolution = rsNoFault,
        createdDaysAgo = 18, updatedDaysAgo = 9,
    )
    event(jobVic20, IssueEventKind.STATUS_CHANGED, elin, 9, value = "Closed")
    comment(jobVic20, elin, "48 hours, rails clean, not one reset. I'd bet on his power supply or his mains, but I can't charge him for a machine that behaved perfectly.", 10)
    comment(jobVic20, otto, "Told him to bring the PSU in next time and we'll test that instead. No charge.", 9)

    val jobQlReturn = job(
        title = "Sinclair QL — microdrive cartridge unreadable, no donor mechanism available",
        description = "Customer wants a single cartridge read. Both drives in the machine are worn and we have no " +
            "serviceable donor mechanism, so there is nothing to read it with.",
        status = stClosed, priority = prWhenItFits, author = otto, assignee = hedvig,
        labels = listOf(lbRecovery), components = listOf(cmSinclair), resolution = rsUnrepaired,
        createdDaysAgo = 17, updatedDaysAgo = 8,
    )
    event(jobQlReturn, IssueEventKind.STATUS_CHANGED, hedvig, 8, value = "Closed")
    comment(jobQlReturn, hedvig, "Returned unread. I've put a note on the shop QL — if I ever get its drives right, we should call this customer back.", 8)

    // ── Ready for pickup ──────────────────────────────────────────────────────

    val jobC64Ready = job(
        title = "C64C — intermittent keyboard columns, membrane replaced",
        description = "Whole columns of keys dead until the case is pressed. Contacts on the membrane are worn " +
            "through where the ribbon meets the board.",
        status = stPickup, priority = prStandard, author = otto, assignee = elin,
        labels = listOf(lbCosmetic), components = listOf(cmCommodore),
        createdDaysAgo = 12, updatedDaysAgo = 2,
    )
    event(jobC64Ready, IssueEventKind.STATUS_CHANGED, elin, 2, value = "Ready for pickup")
    comment(jobC64Ready, elin, "New membrane, every key tested twice. Also cleaned the keycaps while I had them off — it looks twenty years younger.", 2)

    val jobSpectrumPlus2 = job(
        title = "ZX Spectrum +2 — no sound from the AY, headphone socket dirty",
        description = "Beeper works, AY music does not. Suspect the socket rather than the chip, since wiggling " +
            "the plug brings it back briefly.",
        status = stPickup, priority = prWhenItFits, author = otto, assignee = priya,
        components = listOf(cmSinclair), createdDaysAgo = 10, updatedDaysAgo = 1,
    )
    event(jobSpectrumPlus2, IssueEventKind.STATUS_CHANGED, priya, 1, value = "Ready for pickup")
    comment(jobSpectrumPlus2, priya, "It was the socket, as suspected — cleaned and re-tensioned. AY is fine. Cheapest invoice we'll write this month.", 1)

    // ── Awaiting customer approval ────────────────────────────────────────────

    val jobA2000 = job(
        title = "Amiga 2000 — needs a new supply and a full recap; quote sent",
        description = "Machine runs but the supply is noisy under load and the board's electrolytics are original. " +
            "Quoted for a replacement supply and a complete recap of the motherboard. Waiting on the customer.",
        status = stApproval, priority = prStandard, author = otto, assignee = sam,
        labels = listOf(lbRecap), components = listOf(cmAmiga), createdDaysAgo = 9, updatedDaysAgo = 4,
    )
    event(jobA2000, IssueEventKind.STATUS_CHANGED, otto, 4, value = "Awaiting customer approval")
    comment(jobA2000, sam, "It works today. It will not work in two years. The quote says exactly that, and he can decide.", 5)

    val job520st = job(
        title = "Atari 520ST — RF modulator dead; quoted for an RGB output instead",
        description = "The modulator has failed and a replacement is unobtainable. Offered the customer an RGB " +
            "output to a modern display instead, which is a better picture anyway. Quote sent, awaiting a decision.",
        status = stApproval, priority = prWhenItFits, author = otto, assignee = elin,
        components = listOf(cmAtari), createdDaysAgo = 8, updatedDaysAgo = 3,
    )
    event(job520st, IssueEventKind.STATUS_CHANGED, otto, 3, value = "Awaiting customer approval")
    comment(job520st, otto, "> Customer: \"will it still look like it did in 1986?\"\n\nHonestly answered: better. He is thinking about whether he wants better.", 3)

    // ── On the bench ──────────────────────────────────────────────────────────

    val jobA500Agnus = job(
        title = "Amiga 500 Rev 6A — only 512K detected, Agnus not seeing the expansion",
        description = "Trapdoor RAM is good and tests fine in another machine, so the fault is this side. Reseat " +
            "and inspect the Agnus socket; these develop poor contacts with age and a little corrosion.",
        status = stBench, priority = prStandard, author = otto, assignee = sam,
        labels = listOf(lbChip), components = listOf(cmAmiga), createdDaysAgo = 7, updatedDaysAgo = 1,
    )
    comment(jobA500Agnus, sam, "Socket pins are tarnished. Cleaning them one at a time, which is as tedious as it sounds.", 1)

    val jobAppleIIe = job(
        title = "Apple IIe — two keyboard rows dead, dome switches perished",
        description = "Rows two and three do nothing. The dome switches under those rows have lost their spring. " +
            "Replace the failed domes and test every key against the diagnostic.",
        status = stBench, priority = prStandard, author = otto, assignee = marcus,
        labels = listOf(lbCosmetic), components = listOf(cmApple), createdDaysAgo = 6, updatedDaysAgo = 1,
    )

    val jobPet = job(
        title = "PET 4032 — screen full of garbage, no cursor",
        description = "Sixty pounds of steel and a garbage screen. Video RAM or the character ROM; the pattern " +
            "repeats every 40 columns which points at the video side rather than main memory.",
        status = stBench, priority = prStandard, author = otto, assignee = elin,
        labels = listOf(lbChip), components = listOf(cmCommodore), createdDaysAgo = 6, updatedDaysAgo = 2,
    )
    comment(jobPet, milo, "Can I watch this one? I have never seen inside a PET.", 2)
    comment(jobPet, elin, "> Milo: can I watch\n\nYes. Bring the anti-static mat and do not touch the monitor board.", 2)

    // ── Awaiting parts ────────────────────────────────────────────────────────

    val jobA1200Recap = job(
        title = "Amiga 1200 — battery removed, recap kit on backorder",
        description = "Battery out and the board cleaned before the damage spread; it was caught early. The " +
            "surface-mount capacitor kit is on backorder and the board sits bagged on the parts shelf until it arrives.",
        status = stParts, priority = prStandard, author = otto, assignee = sam,
        labels = listOf(lbBattery, lbRecap), components = listOf(cmAmiga), createdDaysAgo = 16, updatedDaysAgo = 5,
    )
    event(jobA1200Recap, IssueEventKind.STATUS_CHANGED, sam, 12, value = "Awaiting parts")
    comment(jobA1200Recap, sam, "Third week waiting on the kit. Supplier now says the end of the month. Customer has been told and is being very patient about it.", 5)

    val jobC128Vdc = job(
        title = "C128 — 80-column mode blank, VDC video RAM on order",
        description = "40-column mode is perfect, 80-column is a black screen. The VDC's own RAM has failed. " +
            "Ordered a pair of 4416s so there is a spare for the next one.",
        status = stParts, priority = prStandard, author = otto, assignee = elin,
        labels = listOf(lbChip), components = listOf(cmCommodore), createdDaysAgo = 14, updatedDaysAgo = 6,
    )
    event(jobC128Vdc, IssueEventKind.STATUS_CHANGED, elin, 9, value = "Awaiting parts")

    val jobMacClassic = job(
        title = "Macintosh Classic — dead supply, waiting on the capacitor kit",
        description = "No sound, no video, no fan. The supply is completely dead and the capacitors are visibly " +
            "bulging. Kit ordered; nothing else can be assessed until it powers up.",
        status = stParts, priority = prStandard, author = otto, assignee = marcus,
        labels = listOf(lbRecap), components = listOf(cmApple), createdDaysAgo = 13, updatedDaysAgo = 7,
    )
    event(jobMacClassic, IssueEventKind.STATUS_CHANGED, marcus, 10, value = "Awaiting parts")

    val jobCpc6128 = job(
        title = "Amstrad CPC 6128 — disc drive will not seek, belt and heads both suspect",
        description = "Drive spins but the head never moves. Belt is the obvious suspect; if the stepper has " +
            "seized as well this becomes a much longer job. Belt ordered.",
        status = stParts, priority = prWhenItFits, author = otto, assignee = priya,
        labels = listOf(lbDrive), components = listOf(cmAmstrad), createdDaysAgo = 11, updatedDaysAgo = 5,
    )
    event(jobCpc6128, IssueEventKind.STATUS_CHANGED, priya, 8, value = "Awaiting parts")

    // ── Diagnosis ─────────────────────────────────────────────────────────────

    val jobSe30 = job(
        title = "Macintosh SE/30 — simasimac, almost certainly the surface-mount capacitors",
        description = "Vertical stripes and a chime that is not quite right — the SE/30's signature failure. The " +
            "surface-mount capacitors leak and eat the traces beneath them. Assess the trace damage before quoting.",
        status = stDiagnosis, priority = prStandard, author = otto, assignee = marcus,
        labels = listOf(lbRecap), components = listOf(cmApple), createdDaysAgo = 5, updatedDaysAgo = 1,
    )
    comment(jobSe30, marcus, "Under the microscope now. Two traces near the audio section are already gone. I'd rather find all of it before quoting than come back for more money later.", 1)

    val jobA1200Guru = job(
        title = "Amiga 1200 — Guru Meditation on floppy access, intermittent",
        description = "Runs from the hard drive for hours without complaint, then gurus within a minute of any " +
            "floppy access. Could be the drive, could be the controller, could be marginal RAM being touched by the DMA.",
        status = stDiagnosis, priority = prStandard, author = otto, assignee = sam,
        components = listOf(cmAmiga), createdDaysAgo = 4, updatedDaysAgo = 1,
    )
    comment(jobA1200Guru, nadia, "Swap in a known-good drive first. Cheapest thing to eliminate and it's wrong surprisingly often.", 1)

    val jobMsx2 = job(
        title = "MSX2 — cartridge slot unreliable, works if you hold the cartridge down",
        description = "Games run perfectly while the cartridge is pressed and drop out the moment it is released. " +
            "Slot contacts, or a cracked joint on the slot's solder side.",
        status = stDiagnosis, priority = prWhenItFits, author = otto, assignee = priya,
        components = listOf(cmAmstrad), createdDaysAgo = 4, updatedDaysAgo = 2,
    )

    // ── Intake — booked in this week, not yet looked at ───────────────────────

    val jobClearance = job(
        title = "House clearance, Ekerö — a garage of Commodore gear to triage",
        description = "Eleven machines and a stack of peripherals from an estate. The family wants whatever can be " +
            "made to work sold on, and the rest recycled responsibly. Triage each machine as its own job under this one.",
        status = stIntake, priority = prWhenItFits, author = janeway, assignee = otto,
        labels = listOf(lbRework), components = listOf(cmCommodore, cmPeripherals),
        createdDaysAgo = 3, updatedDaysAgo = 1,
    )
    comment(jobClearance, janeway, "I've agreed a flat rate for the lot rather than per machine. Anything genuinely unsalvageable goes for parts — label the good chips as you pull them.", 3)

    val jobClearance64 = job(
        title = "Clearance: C64 breadbin, untested",
        description = "From the Ekerö clearance. Case is yellowed and there is a smell of old smoke, but the board " +
            "looks clean. Power it up on the current-limited supply first.",
        status = stIntake, priority = prWhenItFits, author = otto, assignee = null,
        labels = listOf(lbCosmetic), components = listOf(cmCommodore), createdDaysAgo = 3,
    )
    val jobClearance1541 = job(
        title = "Clearance: 1541 with a disk still in it",
        description = "From the Ekerö clearance. There is a disk in the drive, unlabelled. Image it before doing " +
            "anything else — the family may want whatever is on it.",
        status = stIntake, priority = prWhenItFits, author = otto, assignee = null,
        labels = listOf(lbRecovery), components = listOf(cmPeripherals), createdDaysAgo = 3,
    )
    val jobClearance1702 = job(
        title = "Clearance: 1702 monitor, no picture",
        description = "From the Ekerö clearance. Dead as delivered. Worth an hour of anyone's time — a working " +
            "1702 is worth more than the rest of the crate together.",
        status = stIntake, priority = prWhenItFits, author = otto, assignee = null,
        labels = listOf(lbRecap), components = listOf(cmPeripherals), createdDaysAgo = 3,
    )

    val jobCd32 = job(
        title = "Amiga CD32 — tray will not eject",
        description = "Booked in over the counter this morning. The lid mechanism does not release. Customer has " +
            "a disc trapped inside that he would quite like back.",
        status = stIntake, priority = prStandard, author = otto, assignee = null,
        components = listOf(cmAmiga), createdDaysAgo = 1,
    )
    val jobQlShop = job(
        title = "Shop QL — rebuild both microdrives, whenever it is quiet",
        description = "Our own machine, bought as a parts donor and never finished. If the microdrives can be made " +
            "reliable we could offer cartridge reads as a service, which nobody else nearby does. No deadline, ever.",
        status = stIntake, priority = prShelf, author = hedvig, assignee = hedvig,
        labels = listOf(lbDrive), components = listOf(cmSinclair), createdDaysAgo = 214, updatedDaysAgo = 41,
    )
    comment(jobQlShop, hedvig, "Still on the shelf. I get an hour on it about twice a year and I am not sorry about that.", 41)

    // ── The clearance crate as an epic ────────────────────────────────────────
    //
    // Epics are not a development idea: a crate of machines from one estate is one
    // job with eleven children, and the board says so.

    fun makeChild(parent: DemoIssue, child: DemoIssue, index: Int) {
        child.parentId = parent.id
        child.childIndex = index.toDouble()
    }
    makeChild(jobClearance, jobClearance64, 0)
    makeChild(jobClearance, jobClearance1541, 1)
    makeChild(jobClearance, jobClearance1702, 2)

    // ── Two jobs that depend on one another (LNL-215) ─────────────────────────
    //
    // A workshop's version of the same two shapes the Amiga board shows:
    //
    //  - The C128 is **blocked by** the Ekerö crate, which is still sitting in Intake and
    //    therefore still open — so the C128 dims on the board and names what it is waiting
    //    for. That is the honest bench answer: the VDC RAM is on order, but there may be a
    //    donor C128 in that garage and nobody will know until somebody triages it. Note
    //    that a *parent* would have been the wrong tool here — the C128 is not part of the
    //    crate, it is merely waiting on it, and the crate's three children genuinely are
    //    its contents.
    //  - The two Amiga 1200s are **related**, symmetrically: neither causes the other, and
    //    the link exists so that whoever picks up the second reads the first one's bench
    //    log. "Related to" says exactly that from both ends and nothing more.
    relate(jobC128Vdc, rkBlockedBy, jobClearance, elin, 4)
    relate(jobA1200Guru, rkRelatedTo, jobA1200Recap, sam, 5)

    // ── Notifications ─────────────────────────────────────────────────────────
    //
    // A couple from this board, so the bell mixes projects rather than speaking for
    // the Amiga board alone.

    fun key(issue: DemoIssue) = "${p.prefix}-${issue.number}"
    w.notifications.add(
        DemoNotification(
            id = w.allocId(),
            kind = NotificationKind.ISSUE_UPDATED,
            title = "Sam Okonkwo commented on ${key(jobA1200Recap)}",
            createdAt = hoursAgo(9),
            isRead = false,
            projectId = p.id,
            issueId = jobA1200Recap.id,
        ),
    )
    w.notifications.add(
        DemoNotification(
            id = w.allocId(),
            kind = NotificationKind.ISSUE_CREATED,
            title = "Otto Lindqvist booked in ${key(jobCd32)}",
            createdAt = hoursAgo(26),
            isRead = true,
            projectId = p.id,
            issueId = jobCd32.id,
        ),
    )
}
