/**
 * Deploy-time branding: serving a self-hosted instance's brand directory, and
 * splicing its favicon / font stylesheet / title into `index.html` at serve time.
 *
 * The whole feature hangs off one path, `LUNICLE_BRAND_DIR` (see
 * [resolveBrandDir]) — a directory of files a deployment drops in to re-skin the
 * tracker without a single company specific entering the source. Unset ⇒ nothing
 * here runs, [brandRoutes] is never mounted, and the served page is byte-for-byte
 * what it was before this file existed.
 *
 * ── Backend-neutral by construction ─────────────────────────────────────────
 *
 * Branding is read-only deployment config, applied at load and *never* written to
 * the datastore. This module only reads files off [BrandInfo.dir] and serves them;
 * the client injects the themes into its in-memory effective set (see
 * ThemePersister on the web side). So this whole feature stays out of the data
 * layer — which is the point: LNL-111's store work never has to account for it.
 *
 * ── The directory contract ──────────────────────────────────────────────────
 *
 * ```
 * $LUNICLE_BRAND_DIR/
 * ├── brand.json        # manifest (all fields optional)
 * ├── themes/           # any number of kotlinx-serialized lunula Themes (*.json)
 * ├── logo.svg          # topbar + sign-in logo
 * ├── favicon.png       # browser-tab icon
 * └── fonts/
 *     ├── *.woff2        # font files
 *     └── fonts.css      # @font-face declarations supplying the bytes
 * ```
 *
 * ── The `font` manifest block ────────────────────────────────────────────────
 *
 * `brand.json`'s optional `fonts` array names the deployment faces and which
 * surfaces each defaults (the `@font-face` bytes come from `fonts/fonts.css`).
 * The legacy singular `font` object is still accepted for one face:
 *
 * ```
 * "fonts": [
 *   { "family": "Framna Sans",  "surfaces": ["chrome", "prose"] },
 *   { "family": "Framna Serif", "surfaces": ["display"], "fallback": "serif" }
 * ]
 * ```
 *
 *  - `chrome`   → sidebar / tab strip / pane headers / topbar.
 *  - `prose`    → the proportional main-content surface: card text, dialog copy,
 *                 rendered descriptions (aliases: `content-prop`, `content`).
 *  - `display`  → the heading surface: issue titles and board card titles
 *                 (alias: `heading`). (LNL-142)
 *
 * Code / fixed-width content (diffs, code blocks, ticket numbers) is deliberately
 * never branded — it stays monospaced — so there is no `mono` surface (LNL-118).
 * `fallback` (`sans-serif` | `serif` | `monospace`, default `sans-serif`) picks
 * the generic the face degrades to if its `@font-face` is missing.
 *
 * A surface no face names keeps Lunicle's own default there (display falls back
 * to prose). Every surface default yields to a font the user picks in Appearance,
 * and an unbranded instance renders byte-identical to stock.
 *
 * Every part is independently optional. The path is deliberately a *path*, not a
 * disk assumption: `/data/brand` on a mounted volume today, a baked
 * `/opt/lunicle/brand` under a diskless Cloud Run deploy tomorrow — the same
 * code covers both because it never hardcodes `/data`.
 *
 * @see resolveBrandDir
 * @see brandRoutes
 * @see brandedIndexHtml
 */
package se.soderbjorn.lunicle

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The brand directory, or null when branding is off.
 *
 * The two-tier property-over-env lookup every other switch in this server uses
 * (see [resolveDatabaseLocation], [resolveEmailTransport], OAuthConfig), for the
 * same reason: `:server:run` is a Gradle `JavaExec` inheriting the long-lived
 * daemon's environment, so a per-invocation system property is the only override
 * that cannot silently go stale.
 *
 * Blank is absent, as everywhere. A configured path that does not point at a real
 * directory returns null too — a typo'd brand dir degrades to the default look
 * rather than serving 404s that read as a half-applied brand; [main] logs which
 * happened.
 */
internal fun resolveBrandDir(): File? {
    val raw = System.getProperty("lunicle.brandDir")?.takeIf { it.isNotBlank() }
        ?: System.getenv("LUNICLE_BRAND_DIR")?.takeIf { it.isNotBlank() }
        ?: return null
    val dir = File(raw)
    return if (dir.isDirectory) dir else null
}

/** Lenient reader for the manifest and theme files: unknown/missing keys are fine. */
private val brandJson = Json { ignoreUnknownKeys = true }

/**
 * Everything the server needs to know about a brand directory, read once at
 * startup: what to log, what to splice into `<head>`, and which theme files the
 * client should fetch.
 *
 * @property dir         the resolved brand directory.
 * @property title       optional `<title>` override from `brand.json`.
 * @property fontFamily  optional font family name (for the startup log only —
 *   the actual `@font-face` lives in the served `fonts.css`).
 * @property themeFiles  the `*.json` basenames under `themes/`, sorted — the list
 *   the client enumerates (surfaced in the enriched `brand.json` response).
 * @property themeNames  each theme file's `name` field, for the startup log.
 * @property hasLogo     whether `logo.svg` exists.
 * @property hasFavicon  whether `favicon.png` exists.
 * @property hasFontsCss whether `fonts/fonts.css` exists.
 * @property domain the organisation's own domain (LNL-192), or null. **Identity
 *   only**: the sole input to `users.kind`, and nothing else reads it. Falls back to
 *   the legacy [googleHostedDomain] when the manifest does not name it — see
 *   [InstanceIdentity] for the whole compatibility story.
 * @property onlyHostedGoogleAccounts whether the Google chooser is pinned to
 *   [domain] (LNL-125, renamed by LNL-192). **Sign-in ergonomics only**: it grants
 *   nothing. Defaults to whether the legacy [googleHostedDomain] was set, which is
 *   exactly what that field used to mean.
 * @property allowEmailCodeSignIn whether a mailed code is offered as a way in.
 *   Defaults to **true** — an unbranded install has every door available. The claim
 *   only ever narrows the truth: a deployment with no mail transport has no code
 *   sign-in whatever this says. See [InstanceIdentity.isCodeSignInAvailable].
 * @property googleHostedDomain the raw legacy field, kept only so the two new ones
 *   can fall back to it and so the boot log can say a manifest is still on it.
 *   Nothing else should read it — [InstanceIdentity.googleHostedDomainPin] is what
 *   the sign-in path takes.
 */
internal data class BrandInfo(
    val dir: File,
    val title: String?,
    val fontFamily: String?,
    val themeFiles: List<String>,
    val themeNames: List<String>,
    val hasLogo: Boolean,
    val hasFavicon: Boolean,
    val hasFontsCss: Boolean,
    val domain: String?,
    val onlyHostedGoogleAccounts: Boolean,
    val allowEmailCodeSignIn: Boolean,
    val googleHostedDomain: String?,
) {
    /** A one-line summary for the startup log, mirroring the sign-in/email lines. */
    fun describe(): String {
        val themes = if (themeNames.isEmpty()) "none" else themeNames.joinToString(", ")
        val identity = "; domain=${domain ?: "(unset — no staff tier)"}" +
            "; google-pin=$onlyHostedGoogleAccounts; code-sign-in=$allowEmailCodeSignIn"
        // Named explicitly when a manifest is still on the retired spelling, because
        // the fix is a two-line edit somebody has to know to make.
        val legacy = if (googleHostedDomain != null) "; (legacy googleHostedDomain=$googleHostedDomain)" else ""
        return "dir=${dir.path}; themes=[$themes]; font=${fontFamily ?: "(default)"}$identity$legacy"
    }
}

/**
 * Read [dir] into a [BrandInfo]. Every read is defensive: a malformed
 * `brand.json` or theme file is skipped rather than fatal, because a broken
 * brand file must degrade the look, never stop the server booting.
 */
internal fun loadBrandInfo(dir: File): BrandInfo {
    val manifest = runCatching {
        File(dir, "brand.json").takeIf { it.isFile }?.readText()
            ?.let { brandJson.parseToJsonElement(it).jsonObject }
    }.getOrNull()

    val title = (manifest?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    // ── Identity, the chooser pin, and the second door (LNL-192) ─────────────
    //
    // Three fields that used to be one. `googleHostedDomain` remains readable as a
    // legacy spelling and seeds both of the ones that replaced it, so a manifest
    // that has not been updated behaves exactly as it did — see InstanceIdentity
    // for why that is the whole compatibility story and why nothing is migrated.
    val googleHostedDomain = manifest.trimmedString("googleHostedDomain")
    val domain = manifest.trimmedString("domain") ?: googleHostedDomain
    val onlyHostedGoogleAccounts = manifest.boolean("onlyHostedGoogleAccounts")
        ?: (googleHostedDomain != null)
    // Defaults to on, so an unbranded install and a brand dir that says nothing
    // about sign-in both keep every way in. It can only narrow: the effective
    // answer ANDs a configured transport, in resolveOAuthConfig.
    val allowEmailCodeSignIn = manifest.boolean("allowEmailCodeSignIn") ?: true
    // Font families named for the boot log only (the client is what actually
    // applies them). Reads the `fonts` array, falling back to the legacy single
    // `font` object, so the log stays accurate across both manifest shapes.
    val fontFamily = run {
        val fromArray = (manifest?.get("fonts") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("family")?.let { f -> (f as? JsonPrimitive)?.contentOrNull } }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
        fromArray ?: (manifest?.get("font") as? JsonObject)
            ?.let { (it["family"] as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
    }

    val themesDir = File(dir, "themes")
    val themeFiles = themesDir.takeIf { it.isDirectory }
        ?.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()
    val themeNames = themeFiles.mapNotNull { name ->
        runCatching {
            brandJson.parseToJsonElement(File(themesDir, name).readText())
                .jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    return BrandInfo(
        dir = dir,
        title = title,
        fontFamily = fontFamily,
        themeFiles = themeFiles,
        themeNames = themeNames,
        hasLogo = File(dir, "logo.svg").isFile,
        hasFavicon = File(dir, "favicon.png").isFile,
        hasFontsCss = File(dir, "fonts/fonts.css").isFile,
        domain = domain,
        onlyHostedGoogleAccounts = onlyHostedGoogleAccounts,
        allowEmailCodeSignIn = allowEmailCodeSignIn,
        googleHostedDomain = googleHostedDomain,
    )
}

/** A non-blank, trimmed string field, or null. Blank is absent, as everywhere here. */
private fun JsonObject?.trimmedString(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

/**
 * A boolean field, or null when the manifest does not name it.
 *
 * Nullable rather than defaulted so each caller states its own default, which is
 * what lets `onlyHostedGoogleAccounts` fall back to the legacy field while
 * `allowEmailCodeSignIn` falls back to true. Accepts a JSON boolean or the strings
 * "true"/"false", because these are hand-written files; anything else is absent,
 * so a typo takes the documented default rather than the opposite of what was meant.
 */
private fun JsonObject?.boolean(key: String): Boolean? =
    when ((this?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

/**
 * Resolve a path made of single [segments] under [base], refusing anything that
 * would escape the directory.
 *
 * The whole point of this function is that a request can never reach a file
 * outside the brand dir. Each segment must be a plain filename — no separators,
 * no `..`, no NUL — and the resolved canonical path is asserted to sit inside
 * [base]'s canonical path. Anything else returns null, which the routes turn into
 * a 404. A symlink inside the dir pointing out is caught by the canonical check.
 *
 * @return the in-bounds [File], or null when the request tried to leave the dir.
 */
internal fun safeChild(base: File, vararg segments: String): File? {
    var current = base
    for (segment in segments) {
        if (segment.isEmpty() || segment == "." || segment == ".." ||
            segment.contains('/') || segment.contains('\\') || segment.any { it.code == 0 }
        ) {
            return null
        }
        current = File(current, segment)
    }
    val canonBase = base.canonicalFile
    val canon = current.canonicalFile
    return if (canon == canonBase || canon.path.startsWith(canonBase.path + File.separator)) canon else null
}

/** Content type for a served brand file, by extension. Unknown ⇒ octet-stream. */
private fun contentTypeFor(name: String): ContentType = when (name.substringAfterLast('.').lowercase()) {
    "json" -> ContentType.Application.Json
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    "css" -> ContentType.Text.CSS
    "woff2" -> ContentType.parse("font/woff2")
    "woff" -> ContentType.parse("font/woff")
    "ttf" -> ContentType.parse("font/ttf")
    "otf" -> ContentType.parse("font/otf")
    else -> ContentType.Application.OctetStream
}

/** Serve [file] if it is an in-bounds regular file, else 404. */
private suspend fun io.ktor.server.application.ApplicationCall.respondBrandFile(file: File?) {
    if (file == null || !file.isFile) {
        respond(HttpStatusCode.NotFound)
        return
    }
    respond(LocalFileContent(file, contentTypeFor(file.name)))
}

/**
 * Mount the `/brand` asset endpoints for [info]'s directory. Only called when
 * branding is on, so the routes simply do not exist for a default deployment.
 *
 * `brand.json` is the one endpoint that is not served verbatim. Two server-computed
 * fields replace what the file says:
 *
 *  - `themes`, the `themes/` filenames, so the client can enumerate the set without
 *    a directory-listing endpoint of its own;
 *  - `googleHostedDomain`, **resolved** to the domain the chooser is actually pinned
 *    to (LNL-192). The file now carries `domain` and `onlyHostedGoogleAccounts`
 *    separately, and combining them is a rule with a legacy fallback in it — so it
 *    is applied once, here, and the client goes on reading one field and passing it
 *    to Google as the `hd` hint. A second copy of that rule in the browser would be
 *    a chooser pinned to a domain the server's own gate disagrees with. Absent from
 *    the response entirely when nothing is pinned.
 *
 * Every other endpoint streams the file straight off disk, guarded by [safeChild] so
 * a request can never read outside the brand dir.
 */
internal fun Route.brandRoutes(info: BrandInfo) {
    val dir = info.dir
    // Only the chooser pin is read below, and that term does not involve mail — so
    // this takes the manifest's own claim about code sign-in rather than threading
    // the transport in to compute a field nothing here looks at.
    val identity = info.toInstanceIdentity(info.allowEmailCodeSignIn)
    route("/brand") {
        get("/brand.json") {
            val file = File(dir, "brand.json")
            if (!file.isFile) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val base = runCatching { brandJson.parseToJsonElement(file.readText()).jsonObject }
                .getOrNull() ?: JsonObject(emptyMap())
            val pin = identity.googleHostedDomainPin
            val enriched = JsonObject(
                base - "googleHostedDomain" +
                    ("themes" to JsonArray(info.themeFiles.map { JsonPrimitive(it) })) +
                    if (pin != null) mapOf("googleHostedDomain" to JsonPrimitive(pin)) else emptyMap(),
            )
            call.respondText(enriched.toString(), ContentType.Application.Json)
        }
        get("/themes/{name}") {
            val name = call.parameters["name"]
            val file = name?.takeIf { it.endsWith(".json") }?.let { safeChild(dir, "themes", it) }
            call.respondBrandFile(file)
        }
        get("/logo.svg") { call.respondBrandFile(safeChild(dir, "logo.svg")) }
        get("/favicon.png") { call.respondBrandFile(safeChild(dir, "favicon.png")) }
        get("/fonts/{name}") {
            val name = call.parameters["name"]
            call.respondBrandFile(name?.let { safeChild(dir, "fonts", it) })
        }
    }
}

/**
 * Splice branding into an `index.html` [template] at serve time.
 *
 * Three independent additions to `<head>`, each gated on the file actually being
 * present so a partial brand degrades cleanly:
 *  - `<link rel="icon" href="/brand/favicon.png">` when `favicon.png` exists,
 *  - `<link rel="stylesheet" href="/brand/fonts/fonts.css">` **after** the app's
 *    own `styles.css` link when `fonts/fonts.css` exists, so the brand's
 *    `--mono` binding wins the cascade,
 *  - a `<title>` override when `brand.json` set one.
 *
 * Returns the template unchanged when [info] adds nothing, so the served bytes
 * match the default page exactly whenever the brand happens to be logo-only.
 */
internal fun brandedIndexHtml(template: String, info: BrandInfo): String {
    var html = template

    if (info.title != null) {
        val replaced = Regex("<title>.*?</title>", RegexOption.DOT_MATCHES_ALL)
            .replaceFirst(html, "<title>${escapeHtml(info.title)}</title>")
        // No <title> to replace (unexpected, but never crash): fall through and
        // let the <head> injection below add one instead.
        html = if (replaced != html) replaced else html
    }

    // Everything that belongs in <head>, in one splice before </head>. The
    // fonts.css link lands after styles.css purely by virtue of being injected
    // at the end of the head, after the template's own <link ... styles.css>.
    val headAdditions = buildString {
        if (info.hasFavicon) append("    <link rel=\"icon\" href=\"/brand/favicon.png\">\n")
        if (info.hasFontsCss) append("    <link rel=\"stylesheet\" href=\"/brand/fonts/fonts.css\">\n")
        if (info.title != null && !html.contains("<title>")) {
            append("    <title>${escapeHtml(info.title)}</title>\n")
        }
    }
    if (headAdditions.isNotEmpty() && html.contains("</head>")) {
        html = html.replaceFirst("</head>", "$headAdditions</head>")
    }
    return html
}

/** Minimal HTML-text escape for the title, which is the only untrusted value spliced in. */
private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * Read the `index.html` the static server would otherwise serve, so branding can
 * template it. Mirrors the two static-serving flows in [Application.module]: from
 * the `-Dlunicle.webDist` directory in dev, or from `/web` inside the jar when
 * packaged. Null when neither is readable — the caller then leaves the untemplated
 * static route in charge.
 */
internal fun readIndexTemplate(webDistPath: String?): String? =
    if (webDistPath != null) {
        File(webDistPath, "index.html").takeIf { it.isFile }?.readText()
    } else {
        BrandInfo::class.java.classLoader?.getResourceAsStream("web/index.html")
            ?.bufferedReader()?.use { it.readText() }
    }
