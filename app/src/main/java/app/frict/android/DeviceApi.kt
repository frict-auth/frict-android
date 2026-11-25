package app.frict.android

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val HTTP_UPGRADE_REQUIRED = 426

/** Every request carries the app's versionCode, outside the signed bytes. */
const val APP_VERSION_HEADER = "Frict-App-Version"

sealed interface DeviceResponse {
    data class Ok(val status: Int, val body: String) : DeviceResponse
    data class NetworkError(val message: String) : DeviceResponse
}

/**
 * HTTP 426 `app_update_required`: the server refuses this app version. Every request's
 * result can be this, and nothing else is done with that request.
 */
data object UpdateRequired : DeviceResponse, PollUpdate, AnswerResult, FidResult, ServerRemoval, RemoveResult, EnrollResult

/** frict-server's device API at [baseUrl], `https://<api host>` in the app. Calls block. */
class DeviceApi(private val baseUrl: String, private val appVersion: Int = BuildConfig.VERSION_CODE) {
    fun post(path: String, body: ByteArray, authorization: String): DeviceResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(baseUrl + path).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty(APP_VERSION_HEADER, appVersion.toString())
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            updateRequired(status, text) ?: DeviceResponse.Ok(status, text)
        } catch (e: IOException) {
            DeviceResponse.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}

private fun updateRequired(status: Int, body: String): UpdateRequired? = try {
    UpdateRequired.takeIf { status == HTTP_UPGRADE_REQUIRED && JSONObject(body).optString("error") == "app_update_required" }
} catch (_: JSONException) {
    null
}
