rootProject.name = "Lunicle"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Committed file-Maven-repo holding lunula artifacts. Lets
        // Lunicle build with no lunula checkout on disk. Refresh
        // from the toolkit checkout with `./gradlew publishAllToLibsRepo`.
        maven {
            name = "lunulaLibsLocal"
            url = uri("libs-repo")
        }
    }
}

// Auto-detect a sibling lunula checkout. When present, switch to a
// Gradle composite build so toolkit edits flow into Lunicle with no extra
// steps. Pass -Plunula.toolkit.useArtifacts=true to force resolution from
// the committed libs-repo even when sources are present (verifies published
// artifacts). Pass -Plunula.toolkit.path=… to point at an explicit checkout.
val toolkitOverride: String? = settings.providers.gradleProperty("lunula.toolkit.path").orNull
val useArtifacts: Boolean = settings.providers.gradleProperty("lunula.toolkit.useArtifacts").orNull == "true"
// `../../lunula/…` is right from a normal checkout and wrong from a git
// worktree, which sits three levels further down at
// `<repo>/.claude/worktrees/<name>`. So the sibling is searched for by walking
// up from rootDir rather than by counting `..` — the count is a fact about
// where the build happens to be running, and getting it wrong fails silently:
// resolution falls back to the committed libs-repo and the toolkit edits you
// are testing are simply not in the bundle, with a green build to say so.
//
// Bounded at eight levels, which is far past any real layout and stops this
// from walking to the filesystem root on a machine with no lunula at all.
val toolkitCandidates: List<String> = listOfNotNull(toolkitOverride) +
    (0..8).flatMap { depth ->
        val up = "../".repeat(depth)
        listOf("${up}../lunula/develop", "${up}../lunula/main")
    }
val toolkitPath: String? = if (useArtifacts) null else toolkitCandidates
    .firstOrNull { File(rootDir, it).resolve("settings.gradle.kts").exists() }
if (toolkitPath != null) {
    includeBuild(toolkitPath) {
        dependencySubstitution {
            substitute(module("se.soderbjorn.lunula:lunula-core")).using(project(":lunula-core"))
            substitute(module("se.soderbjorn.lunula:lunula-store")).using(project(":lunula-store"))
            substitute(module("se.soderbjorn.lunula:lunula-web")).using(project(":lunula-web"))
            substitute(module("se.soderbjorn.lunula:lunula-compose")).using(project(":lunula-compose"))
        }
    }
}

include(":clientServer")
include(":client")
include(":server")
include(":web")
