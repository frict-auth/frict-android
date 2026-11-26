package app.frict.android

private val CHALLENGE_ID = Regex("[A-Za-z0-9_-]{22}")

/** A login push (frict-server docs/device-protocol.md, Push): which challenge, for which enrolled user. */
data class LoginPush(val challengeId: String, val userId: String)

/** The login push [data] names; any other field is ignored. Null without a valid challenge and user ID. */
fun parsePush(data: Map<String, String>): LoginPush? {
    val challengeId = data["challenge_id"]?.takeIf { CHALLENGE_ID.matches(it) } ?: return null
    val userId = data["user_id"]?.takeIf { USER_ID.matches(it) } ?: return null
    return LoginPush(challengeId, userId)
}

data class OwnedChallenge(val account: Account, val challenge: Challenge)

/**
 * The challenge [push] names, fetched from the server of the one account with its user ID,
 * and no other. Null, without a request, unless exactly one account has that user ID.
 */
fun pushedChallenge(push: LoginPush, accounts: List<Account>, approvals: (Account) -> Approvals): OwnedChallenge? {
    val account = accounts.singleOrNull { it.userId == push.userId } ?: return null
    return (approvals(account).open(push.challengeId) as? PollUpdate.Show)?.let { OwnedChallenge(account, it.challenge) }
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
