package app.frict.android

private val CHALLENGE_ID = Regex("[A-Za-z0-9_-]{22}")

/**
 * The challenge ID a login push names (frict-server docs/device-protocol.md, Push); any
 * other field is ignored. Null for anything else.
 */
fun parsePush(data: Map<String, String>): String? = data["challenge_id"]?.takeIf { CHALLENGE_ID.matches(it) }

data class OwnedChallenge(val account: Account, val challenge: Challenge)

/**
 * The account a pushed challenge [id] belongs to: the first whose server returns it to that
 * account's device key, which a server does only for its owner. Null if none does.
 */
fun findOwner(id: String, accounts: List<Account>, approvals: (Account) -> Approvals): OwnedChallenge? =
    accounts.firstNotNullOfOrNull { account ->
        (approvals(account).open(id) as? PollUpdate.Show)?.let { OwnedChallenge(account, it.challenge) }
    }

/**
 * Sends [fid] to every account's server, returning what each said; one account's failure,
 * such as a lost device_key, doesn't stop the rest.
 */
fun sendFidToAll(fid: String, accounts: List<Account>, approvals: (Account) -> Approvals): Map<Account, String> =
    accounts.associateWith {
        try {
            approvals(it).sendFid(fid).toString()
        } catch (e: Exception) {
            "not sent: ${e.message ?: e.javaClass.simpleName}"
        }
    }
