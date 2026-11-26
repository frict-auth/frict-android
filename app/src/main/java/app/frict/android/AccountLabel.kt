package app.frict.android

const val MAX_LABEL_LENGTH = 64

/** `<username> @ <hostname>`, or the [legacyName] form if the server sent no username. */
fun defaultLabel(username: String, host: String, userId: String): String =
    if (username.isBlank()) legacyName(host, userId) else capLabel("${username.trim()} @ ${hostname(host)}")

/** What the person enrolling typed, trimmed and capped; [default] if they left it empty. */
fun chosenLabel(edited: String, default: String): String = capLabel(edited.trim().ifEmpty { default })

/** How the app names [this]: its label, or for an account enrolled before labels, its hostname and the start of its user ID. */
val Account.name: String get() = label ?: legacyName(host, userId)

/**
 * Saves [edited] as the label of [account], which enrolling saved with its default label.
 * Returns the account as saved, or null if it could not be saved.
 */
fun nameAccount(account: Account, edited: String, store: AccountStore): Account? {
    val named = account.copy(label = chosenLabel(edited, account.name))
    return named.takeIf { store.replace(account, it) }
}

private fun legacyName(host: String, userId: String) = "${hostname(host)} · ${userId.take(8)}"

private fun hostname(host: String) = host.substringBefore(':')

/** [s] cut to [MAX_LABEL_LENGTH] characters, never inside one. */
fun capLabel(s: String): String =
    if (s.codePointCount(0, s.length) <= MAX_LABEL_LENGTH) s else s.substring(0, s.offsetByCodePoints(0, MAX_LABEL_LENGTH))
