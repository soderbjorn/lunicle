package se.soderbjorn.lunicle

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deploy-time branding seam (LNL-110): the path-traversal guard, the
 * directory reader, the `brand.json` enrichment served to the client, and the
 * `index.html` head templating.
 *
 * Everything here is a pure function of a temp directory or a string, so the
 * whole feature is asserted without System properties or a live volume — the
 * property-over-env resolution in [resolveBrandDir] is the same idiom
 * [EmailTransportTest] leaves to its own resolver, and is not re-tested here.
 */
class BrandRoutesTest {

    private fun brandDir(): File {
        val dir = Files.createTempDirectory("brand").toFile()
        File(dir, "brand.json").writeText(
            """{"defaultDarkTheme":"Acme Dark","defaultLightTheme":"Acme Light",""" +
                """"font":{"family":"Acme Sans","surfaces":["content","chrome"]},"title":"Acme Issues",""" +
                """"googleHostedDomain":"acme.com"}""",
        )
        File(dir, "themes").mkdirs()
        File(dir, "themes/acme-dark.json").writeText("""{"name":"Acme Dark"}""")
        File(dir, "themes/acme-light.json").writeText("""{"name":"Acme Light"}""")
        File(dir, "logo.svg").writeText("<svg></svg>")
        File(dir, "favicon.png").writeText("PNG")
        File(dir, "fonts").mkdirs()
        File(dir, "fonts/fonts.css").writeText(":root{--mono:'Acme Sans';}")
        File(dir, "secret.txt").writeText("do not serve me")
        return dir
    }

    // ── Path-traversal guard ─────────────────────────────────────────────────

    @Test
    fun `safeChild resolves an in-bounds file`() {
        val dir = brandDir()
        val file = safeChild(dir, "themes", "acme-dark.json")
        assertEquals(File(dir, "themes/acme-dark.json").canonicalFile, file)
    }

    @Test
    fun `safeChild refuses dot-dot, separators and escapes`() {
        val dir = brandDir()
        assertNull(safeChild(dir, "themes", ".."), "..")
        assertNull(safeChild(dir, "..", "secret.txt"), "climbing out")
        assertNull(safeChild(dir, "themes", "../../secret.txt"), "encoded climb in one segment")
        assertNull(safeChild(dir, "themes/acme-dark.json"), "embedded separator")
        assertNull(safeChild(dir, "."), "a bare dot")
    }

    // ── Directory reader ─────────────────────────────────────────────────────

    @Test
    fun `loadBrandInfo reads manifest, theme names and file presence`() {
        val info = loadBrandInfo(brandDir())
        assertEquals("Acme Issues", info.title)
        assertEquals("Acme Sans", info.fontFamily)
        assertEquals(listOf("acme-dark.json", "acme-light.json"), info.themeFiles)
        assertEquals(listOf("Acme Dark", "Acme Light"), info.themeNames)
        assertEquals("acme.com", info.googleHostedDomain)
        assertTrue(info.hasLogo)
        assertTrue(info.hasFavicon)
        assertTrue(info.hasFontsCss)
    }

    @Test
    fun `loadBrandInfo on an empty dir reports nothing without failing`() {
        val info = loadBrandInfo(Files.createTempDirectory("empty-brand").toFile())
        assertNull(info.title)
        assertNull(info.fontFamily)
        assertNull(info.googleHostedDomain)
        assertTrue(info.themeFiles.isEmpty())
        assertFalse(info.hasLogo)
    }

    // ── brand.json enrichment + serving ──────────────────────────────────────

    @Test
    fun `brand json is served enriched with the themes array, and traversal is refused`() = testApplication {
        val dir = brandDir()
        application { routing { brandRoutes(loadBrandInfo(dir)) } }

        val manifest = Json.parseToJsonElement(client.get("/brand/brand.json").bodyAsText()).jsonObject
        assertEquals("Acme Dark", manifest["defaultDarkTheme"]!!.jsonPrimitive.content)
        // The client reads googleHostedDomain (LNL-125) straight off this response
        // for the chooser hint, so the enrichment must forward it untouched.
        assertEquals("acme.com", manifest["googleHostedDomain"]!!.jsonPrimitive.content)
        val themes: JsonArray = manifest["themes"]!!.jsonArray
        assertEquals(
            listOf("acme-dark.json", "acme-light.json"),
            themes.map { it.jsonPrimitive.content },
        )

        assertEquals(HttpStatusCode.OK, client.get("/brand/themes/acme-dark.json").status)
        assertEquals(HttpStatusCode.OK, client.get("/brand/logo.svg").status)
        // A non-json themes request, and a name that is not a real file, both 404.
        assertEquals(HttpStatusCode.NotFound, client.get("/brand/themes/nope.txt").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/brand/themes/missing.json").status)
        // An encoded traversal never reaches secret.txt.
        assertEquals(HttpStatusCode.NotFound, client.get("/brand/fonts/%2e%2e%2fsecret.txt").status)
    }

    // ── index.html templating ────────────────────────────────────────────────

    private val template = """
        <!DOCTYPE html>
        <html><head>
            <title>Lunicle</title>
            <link type="text/css" rel="stylesheet" href="styles.css">
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun `branded index injects favicon, fonts after styles, and overrides title`() {
        val html = brandedIndexHtml(template, loadBrandInfo(brandDir()))
        assertTrue(html.contains("<title>Acme Issues</title>"), "title overridden")
        assertFalse(html.contains("<title>Lunicle</title>"), "old title gone")
        assertTrue(html.contains("""<link rel="icon" href="/brand/favicon.png">"""), "favicon")
        assertTrue(html.contains("""<link rel="stylesheet" href="/brand/fonts/fonts.css">"""), "fonts")
        assertTrue(
            html.indexOf("styles.css") < html.indexOf("fonts.css"),
            "brand fonts.css loads after styles.css so its --mono wins",
        )
    }

    @Test
    fun `a logo-only brand leaves index html byte-for-byte unchanged`() {
        val dir = Files.createTempDirectory("logo-only").toFile()
        File(dir, "logo.svg").writeText("<svg></svg>")
        assertEquals(template, brandedIndexHtml(template, loadBrandInfo(dir)))
    }

    // ── The brand's favicon against the app's own ────────────────────────────

    /**
     * The real page, read off disk rather than typed out here.
     *
     * The two tests below are about a regex matching the icon links index.html
     * actually declares, so a copy of those links in this file would assert that
     * the regex matches the copy — which it would go on doing after somebody
     * reformatted the page, single-quoted a `rel`, or added a fourth icon.
     *
     * Gradle runs a module's tests with the module directory as the working
     * directory, which is what the `..` climbs out of.
     */
    private fun realIndexHtml(): String =
        File("../web/src/jsMain/resources/index.html").also {
            assertTrue(it.isFile, "index.html not found at ${it.absolutePath}")
        }.readText()

    @Test
    fun `a brand favicon replaces the app's own icon links rather than joining them`() {
        val html = brandedIndexHtml(realIndexHtml(), loadBrandInfo(brandDir()))

        // The brand's is the only icon left. Not "is present" — a browser given
        // both picks the SVG, so the default set surviving is the whole bug.
        //
        // Asserted on the `href`/`rel` rather than on the bare filenames: the
        // page's comment about where those files come from names every one of
        // them, stays in the served bytes, and would satisfy a `contains` check
        // for "icon.svg" whether the link was still there or not.
        assertTrue(html.contains("""<link rel="icon" href="/brand/favicon.png">"""), "brand favicon")
        assertFalse(html.contains("""href="icon.svg""""), "the default SVG icon link survived")
        assertFalse(html.contains("""href="favicon-32.png""""), "the default png icon link survived")
        assertFalse(html.contains("""rel="apple-touch-icon""""), "the default apple-touch link survived")

        // Only the icon links go. The stylesheet is a <link> too, and the brand's
        // fonts.css still has to land after it.
        assertTrue(html.contains("""<link type="text/css" rel="stylesheet" href="styles.css">"""), "styles.css")
        assertTrue(
            html.indexOf("styles.css") < html.indexOf("fonts.css"),
            "brand fonts.css loads after styles.css so its --mono wins",
        )
    }

    @Test
    fun `a brand with no favicon leaves the app's own icon links alone`() {
        val dir = Files.createTempDirectory("no-favicon").toFile()
        File(dir, "logo.svg").writeText("<svg></svg>")
        val page = realIndexHtml()

        // Nothing to replace them with, so removing them would leave the tab with
        // no icon at all — worse than the default and invisible until deployed.
        assertEquals(page, brandedIndexHtml(page, loadBrandInfo(dir)))
    }
}
