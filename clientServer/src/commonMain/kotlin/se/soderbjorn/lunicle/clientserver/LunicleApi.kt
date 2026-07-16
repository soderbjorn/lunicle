/**
 * The typed HTTP client every platform talks to the Lunicle server through.
 *
 * Constructed once by the app bootstrap (for Stage 1: the browser bundle's
 * `main()`) and handed to the backing view model, which is the only caller.
 * Keeping the transport here rather than in `:client` means the view model
 * never mentions HTTP, and a future platform gets the same wire behaviour for
 * free.
 *
 * @see CounterState
 * @see ApiRoutes
 */
package se.soderbjorn.lunicle.clientserver

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Build the [HttpClient] backing an [LunicleApi].
 *
 * No engine is named: each target has exactly one engine on its classpath (JS
 * for the browser bundle, CIO for the JVM), so Ktor resolves it via the
 * service loader. `ignoreUnknownKeys` keeps an older cached bundle from failing
 * to parse a response that a newer server has added a field to.
 *
 * @return a configured client the caller owns and is responsible for closing.
 */
fun createHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

/**
 * Typed access to the Lunicle server's counter endpoints.
 *
 * @param baseUrl prefix for every request. Defaults to `""` — a relative URL —
 *   which is correct for the browser bundle: the server that serves the bundle
 *   is the server the bundle talks to, and that stays true inside the
 *   lunamux.dev iframe, where the frame's own origin is lunicle.lunamux.dev. So
 *   there is no cross-origin request here and no CORS to configure. Pass an
 *   absolute URL from a JVM caller or a test.
 * @param httpClient the transport; defaults to a fresh [createHttpClient].
 */
class LunicleApi(
    private val baseUrl: String = "",
    private val httpClient: HttpClient = createHttpClient(),
) {
    /**
     * Fetch the current counter value.
     *
     * Called by the view model on start, to render the server's value rather
     * than a guess.
     *
     * @return the server's current [CounterState].
     * @throws Exception on any transport or parse failure; the caller decides
     *   what a failure looks like on screen.
     */
    suspend fun counter(): CounterState =
        httpClient.get(baseUrl + ApiRoutes.COUNTER).body()

    /**
     * Increment the counter and return its new value.
     *
     * Called by the view model when the user taps the button. The server
     * returns the post-increment state, so the client never computes the count
     * itself — it renders what it is told.
     *
     * @return the [CounterState] after the increment.
     * @throws Exception on any transport or parse failure.
     */
    suspend fun increment(): CounterState =
        httpClient.post(baseUrl + ApiRoutes.COUNTER_INCREMENT).body()

    /**
     * Who, if anyone, this browser is signed in as — and which providers this
     * deployment can offer.
     *
     * Never throws for being signed out: that is a [SessionState] with a null
     * user, not a failure. Only transport and parse failures throw.
     *
     * @return the caller's [SessionState].
     */
    suspend fun session(): SessionState =
        httpClient.get(baseUrl + ApiRoutes.SESSION).body()

    /**
     * Trade a Google authorization code for a session.
     *
     * The code comes from the popup, which the *view* owns — Google's SDK is
     * browser-only, so the platform opens the popup and hands the code here.
     * That keeps this API and the view model free of anything Google-shaped.
     *
     * @param code the authorization code from `initCodeClient`'s callback.
     * @return the now signed-in [SessionState].
     * @throws Exception if the server rejects the code or cannot reach Google.
     */
    suspend fun signInWithGoogle(code: String): SessionState =
        httpClient.post(baseUrl + ApiRoutes.AUTH_GOOGLE) {
            contentType(ContentType.Application.Json)
            setBody(GoogleCodeRequest(code))
        }.body()

    /**
     * Drop this browser's session.
     *
     * @return the signed-out [SessionState].
     * @throws Exception on any transport or parse failure.
     */
    suspend fun signOut(): SessionState =
        httpClient.post(baseUrl + ApiRoutes.SIGN_OUT).body()
}
