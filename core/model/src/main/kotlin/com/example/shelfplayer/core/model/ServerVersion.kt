package com.example.shelfplayer.core.model

/**
 * PRODUCT_SPEC 24.4 / ADR-0024 — the oldest Audiobookshelf this app will sign in to.
 *
 * ### Why there is a floor at all, when the capability probe exists
 *
 * SYNC-001's probe answers "does this server do X", which is the right question for a *feature*. It is the
 * wrong question for the **authentication model**, because that is not a feature the app can degrade
 * gracefully without: below 2.26.0 the server returns only the pre-2.26 `user.token`, which is not
 * refreshable. Everything would appear to work, and then AUTH-004's silent renewal would fail hours later
 * on a device, looking to the user like a random sign-out with no cause they could name.
 *
 * A probe cannot save that. `POST /login` succeeds either way and the absence only shows up at the first
 * renewal, so the honest place to refuse is before a password is typed.
 *
 * ### Why 2.26.0 and not 2.36.0
 *
 * 2.36.0 is the version every contract fixture was captured against, and it is tempting to make it the
 * floor on the grounds that nothing else has been verified. That would refuse servers that would very
 * likely work, for the sake of a claim the app cannot substantiate either way — the app has not been tested
 * against 2.30 any more than it has been tested against 2.26. The one thing that *is* known is where the
 * refreshable token arrived, and that is a real behavioural boundary rather than a testing artefact.
 *
 * The owner chose 2.26.0. ADR-0024 records it as a decision with a stated cost: a server between 2.26 and
 * 2.36 is accepted and unverified, and `docs/api-compatibility.md` says so.
 *
 * ### Parsing is deliberately forgiving in one direction only
 *
 * A version this code cannot parse is **allowed through**, not refused. That is the opposite of the
 * fail-closed rule the profile lock follows, and the difference is what failure costs: a lock that fails
 * open exposes an account, while a version gate that fails closed locks a user out of their own working
 * server because it reported `2.36.0-beta.1` or `v2.36` or something nobody anticipated. The gate exists to
 * catch a *known-bad* shape, so it refuses only what it positively recognises as too old.
 */
data class ServerVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ServerVersion> {

    override fun compareTo(other: ServerVersion): Int = when {
        major != other.major -> major - other.major
        minor != other.minor -> minor - other.minor
        else -> patch - other.patch
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * ADR-0024 — the floor. Below this the server issues no refresh token.
         *
         * Named rather than inlined so the one place it is decided is greppable, and so the message shown
         * to a refused user and the check that refused them cannot drift apart.
         */
        val Minimum = ServerVersion(2, 26, 0)

        /**
         * Parses a reported version, or `null` when it does not look like one.
         *
         * Tolerates a leading `v`, a pre-release or build suffix (`2.36.0-beta.1`, `2.36.0+build7`), and a
         * missing patch component (`2.26`). Anything else is `null`, which [isSupported] treats as
         * acceptable — see the class KDoc for why the uncertainty resolves that way here and the opposite
         * way in the lock.
         */
        fun parse(reported: String?): ServerVersion? {
            val trimmed = reported?.trim()?.removePrefix("v")?.takeIf(String::isNotEmpty) ?: return null
            // Stop at the first character that cannot be part of a dotted numeric version, so a
            // pre-release suffix is ignored rather than making the whole string unparseable.
            val numeric = trimmed.takeWhile { it.isDigit() || it == '.' }
            val parts = numeric.split('.').filter(String::isNotEmpty)
            if (parts.isEmpty()) return null
            val numbers = parts.take(COMPONENTS).map { part -> part.toIntOrNull() ?: return null }
            return ServerVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }

        /**
         * Whether a reported version may sign in.
         *
         * `true` for an unparseable or absent version, deliberately. A self-hosted server that reports
         * something unexpected is far more likely to be a working server with an unusual build string than
         * a genuinely ancient one, and refusing it would strand somebody with no way to argue.
         */
        fun isSupported(reported: String?): Boolean {
            val parsed = parse(reported) ?: return true
            return parsed >= Minimum
        }

        private const val COMPONENTS = 3
    }
}
