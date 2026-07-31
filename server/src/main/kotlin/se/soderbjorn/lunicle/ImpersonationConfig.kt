/**
 * The one switch that decides whether owner impersonation exists on this
 * deployment at all.
 *
 * ── What it is for ──────────────────────────────────────────────────────────
 *
 * Impersonation here is not a costume. The owner is signed **out**, hands back a
 * short-lived grant, and is then signed in *for real* as any address they name —
 * the genuine sign-in pipeline, admission gate included, minus only the Google
 * exchange or the mailed code. That is the whole point: it answers "would this
 * address be admitted? would it arrive as staff? what happens the moment it
 * exists?", which a read-only preview cannot. It is also, for exactly that reason,
 * the power to become any account on the instance.
 *
 * So it is meant to be switched on for an afternoon of security testing and
 * switched off again — including on the real instance — rather than left standing.
 *
 * ── Why this one fails closed, when its sibling does not ────────────────────
 *
 * [resolveEmailSignInEnabled] defaults **on** and treats a typo as on, deliberately:
 * it governs behaviour that predates the flag, and failing closed there would
 * silently lock a deployment out of sign-in. This flag has no such history and
 * grants the power to become anybody, so both of those defaults invert. A typo
 * costs you a feature you have to switch on again; the opposite mistake costs you
 * the instance.
 *
 * ── Turning it off is the only cleanup there is ─────────────────────────────
 *
 * Changing an environment variable restarts the process on every host this is
 * deployed to, and that restart *is* the mechanism: grants live in memory only
 * (see [ProbeGrants]), so they die with it, and `Application.module` sweeps every
 * probe-labelled session at boot **unconditionally** — whatever this flag says. So
 * turning the gate off ends every impersonation in flight by construction, with no
 * cleanup code, no migration and no manual step.
 *
 * The one thing that would break that guarantee is a host that hot-reloads
 * environment variables without replacing the process. Do not deploy this behind
 * one, and on Railway never pass `--skip-deploys` when setting the variable: it
 * writes the new value while leaving the running container on the old one, which
 * reports the gate as off while the live process goes on serving these routes with
 * every grant still in memory.
 *
 * @see ProbeGrants
 * @see resolveEmailSignInEnabled the sibling resolver this follows, and inverts.
 */
package se.soderbjorn.lunicle

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ImpersonationConfig")

/**
 * Whether the instance owner may sign in as anybody on this deployment.
 *
 * Reads `LUNICLE_ENABLE_OWNER_IMPERSONATION` (property
 * `lunicle.ownerImpersonation`, which outranks it — see `resolveValue` in
 * OAuthConfig.kt for why both tiers exist), and **defaults to off** when unset.
 * Accepts the same on/off vocabulary every other switch on this server does.
 *
 * An unrecognised value is off, with a WARN naming what was set. That is the
 * opposite call from [resolveEmailSignInEnabled] and the file preamble says why.
 *
 * @return true only when the value is unambiguously an on-value.
 */
internal fun resolveOwnerImpersonationEnabled(): Boolean {
    val raw = ownerImpersonationSetting()?.trim()?.lowercase() ?: return false
    return when (raw) {
        "on", "true", "1", "yes", "enabled" -> true
        "off", "false", "0", "no", "disabled" -> false
        else -> {
            logger.warn(
                "LUNICLE_ENABLE_OWNER_IMPERSONATION is set to \"$raw\", which is neither on nor off; " +
                    "owner impersonation stays disabled. Set it to \"on\" to enable it.",
            )
            false
        }
    }
}

/**
 * The raw setting, property first and then environment.
 *
 * A copy of OAuthConfig's private `resolveValue` rather than a widening of it: that
 * one is private to the file that reads six credentials through it, and making it
 * internal to serve one more caller would put a `System.getProperty` seam on the
 * package for anything to reach. Blank is absent, as it is everywhere on this
 * server — an empty variable is a misconfiguration, not a request for anything.
 */
private fun ownerImpersonationSetting(): String? =
    System.getProperty("lunicle.ownerImpersonation")?.takeIf { it.isNotBlank() }
        ?: System.getenv("LUNICLE_ENABLE_OWNER_IMPERSONATION")?.takeIf { it.isNotBlank() }
