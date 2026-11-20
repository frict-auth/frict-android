package app.frict.android

import java.net.URI
import java.net.URISyntaxException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Someone answered a push with "Not me", as `/device/v1/pending` lists it for an alert
 * recipient. The sign-in's fields are the denied challenge's and may be empty.
 */
data class Alert(
    val id: String,
    val username: String,
    val ipaddr: String,
    val pushinfo: List<Pair<String, String>>,
    val type: String,
    val attempted: Long,
    val reported: Long,
    val resetPasswordUrl: String,
    val revokeSessionsUrl: String,
)

/** Entries without an alert_id are skipped. */
fun parseAlerts(alerts: JSONArray?): List<Alert> {
    if (alerts == null) return emptyList()
    return List(alerts.length()) { alerts.optJSONObject(it) }.mapNotNull { json ->
        json?.optString("alert_id")?.takeIf { it.isNotEmpty() }?.let { id -> parseAlert(id, json) }
    }.distinctBy { it.id }
}

private fun parseAlert(id: String, json: JSONObject) = Alert(
    id = id,
    username = json.optString("username"),
    ipaddr = json.optString("ipaddr"),
    pushinfo = parsePushinfo(json.optString("pushinfo")),
    type = json.optString("type"),
    attempted = json.optLong("attempted"),
    reported = json.optLong("reported"),
    resetPasswordUrl = json.optString("reset_password_url"),
    revokeSessionsUrl = json.optString("revoke_sessions_url"),
)

/**
 * [url] if the app may open it in the browser: an absolute http or https URL with a host
 * and no userinfo. Anything else is shown as text, never opened.
 */
fun openableLink(url: String): String? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
    return url
}
