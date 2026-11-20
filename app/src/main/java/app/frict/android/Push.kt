package app.frict.android

/** What a push names: a challenge or an alert, by ID only (frict-server docs/device-protocol.md, Push). */
sealed interface PushTarget {
    val id: String

    data class Challenge(override val id: String) : PushTarget
    data class Alert(override val id: String) : PushTarget
}

private val PUSH_ID = Regex("[A-Za-z0-9_-]{22}")

/** The ID a push's data names; any other field is ignored. Null for anything else. */
fun parsePush(data: Map<String, String>): PushTarget? {
    data["challenge_id"]?.let { return if (PUSH_ID.matches(it)) PushTarget.Challenge(it) else null }
    data["alert_id"]?.let { return if (PUSH_ID.matches(it)) PushTarget.Alert(it) else null }
    return null
}
