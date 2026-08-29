# ADR-0028 — Admin authority is the session, not a second password

**Status:** Accepted, 2026-08-29. Owner decision. Answers the one open question in `docs/gaps.md` that was
marked *"needs a decision"* rather than *"needs a device"*. Supersedes nothing; narrows nothing in ADR-0023.

## Context

PRODUCT_SPEC §8 item 12 asks for *"profile PIN/biometric protection, especially for admin accounts."*

The protection exists: AUTH-005 and ADR-0023 built a per-profile passcode with an optional biometric
prompt. What the specification's *"especially"* left open is whether an admin account should be **obliged**
to take one — and, beyond that, whether each privileged operation should re-prompt before it runs. Neither
question has a technical answer. Both are the owner's, and both were asked rather than guessed.

The operations at stake are the Phase 5 management tools: removing an item from the server database,
creating a user, editing metadata, embedding metadata into files. Several are destructive and one of them
rewrites audio files.

## Decision

**Being signed in as an admin is sufficient authority. There is no second password.**

Concretely:

1. **No step-up authentication.** No privileged operation re-prompts for a password, a passcode or a
   biometric before it runs. The session is the authority.
2. **No mandatory passcode for admin profiles.** The profile lock stays exactly what ADR-0023 made it —
   optional, per profile, chosen by the person whose profile it is. An admin may take one; nothing obliges
   it, and nothing treats an admin profile differently from any other.

§8 item 12 is satisfied by *offering* the protection to every account including admin ones, which the app
does. It is a recommended feature, not an acceptance criterion, and the recommendation does not say
"mandatory".

## Consequences

**Stated plainly, because this is the cost and it is real.** Somebody holding the unlocked phone while an
admin profile is active can remove an item from the server database, create a user, and rewrite a book's
files, with no further challenge from this app. That is the threat ADR-0023 already described for the
profile lock generally — *"somebody holding the unlocked phone"* — now recorded as accepted for the
privileged operations too.

Three things remain true and are worth not confusing with a passcode:

- **The server is the other gate, and it is not this app's.** Every privileged operation is a server call
  carrying the bearer token, and the server applies its own permissions. An account without admin rights on
  the server cannot perform these operations however this app behaves. The decision here removes a *second*
  local challenge, not the only one.
- **Destructive actions still confirm, and still say what they do.** That is product priority 5 and a
  different protection: it guards a mis-tap, not an attacker. ADR-0021's rule that this app never claims
  the database-delete endpoint removes files is unchanged.
- **The optional lock is still there** for anybody who wants it, and it is still a curtain rather than a
  vault — ADR-0023's own words, and its wording may not be softened because this decision exists.

The alternative was rejected on the owner's judgement about their own use, which is the right basis: a
self-hosted audiobook app on a personal phone is not a shared administrative console, and a passcode prompt
before every metadata edit is a cost paid on every ordinary action to defend against holding the unlocked
device, which the device's own lock screen already governs.

If the app ever grows a genuinely shared-device story — a family tablet with an admin profile on it — this
is the decision to revisit, and step-up authentication before the four management operations is the shape
it would take.
