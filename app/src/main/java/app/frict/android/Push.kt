package app.frict.android

private val CHALLENGE_ID = Regex("[A-Za-z0-9_-]{22}")

/**
 * The challenge ID a login push names (frict-server docs/device-protocol.md, Push); any
 * other field is ignored. Null for anything else.
 */
fun parsePush(data: Map<String, String>): String? = data["challenge_id"]?.takeIf { CHALLENGE_ID.matches(it) }
