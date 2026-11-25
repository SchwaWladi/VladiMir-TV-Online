package com.vladimir.tv

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Client for the classic Stalker/Ministra STB API. Providers can customize endpoints/auth. */
internal class StalkerPortalClient(
    private val portal: String,
    private val macAddress: String,
    private val login: String = "",
    private val password: String = "",
    private val authMode: Int = 0
) {
    data class Category(val id: String, val name: String)
    data class Channel(val id: String, val name: String, val logo: String, val epgId: String, val command: String)

    @Volatile private var token: String = ""
    private val mac = macAddress.trim().uppercase(Locale.ROOT).replace('-', ':')
    private val apiUrl: String = run {
        val source = URL(portal.trim())
        val path = source.path.trimEnd('/')
        val apiPath = when {
            path.endsWith("/server/load.php", true) -> path
            path.endsWith("/stalker_portal", true) -> "$path/server/load.php"
            path.endsWith("/c", true) -> "${path.substringBeforeLast("/c")}/server/load.php"
            else -> "$path/stalker_portal/server/load.php"
        }
        URL(source.protocol, source.host, source.port, apiPath).toString()
    }

    fun verify(): JSONObject {
        require(Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(mac)) { "MAC address must be provider-issued and formatted like 00:1A:79:00:00:00" }
        if (authMode in 1..2) authorizeByCredentials()
        val handshake = js(request("stb", "handshake", mapOf("token" to ""), null))
        token = handshake.optString("token")
        check(token.isNotBlank()) { "The portal did not return an STB token" }
        val profile = js(request("stb", "get_profile", emptyMap(), token))
        check(profile.length() > 0) { "The portal returned an empty STB profile" }
        val status = profile.opt("status")
        val statusText = status?.toString()?.lowercase(Locale.ROOT).orEmpty()
        check(status !is Boolean || status) { "The portal reports an inactive STB" }
        check(status !is Number || status.toInt() != 0) { "The portal reports an inactive STB" }
        check(statusText !in setOf("disabled", "blocked", "inactive", "expired", "error", "false")) {
            "The portal reports an inactive account or device"
        }
        check(statusText != "0") { "The portal reports an inactive STB" }
        return profile
    }

    fun categories(): List<Category> {
        ensureToken()
        return rows(request("itv", "get_genres", emptyMap(), token)).mapNotNull { row ->
            val id = row.optString("id", row.optString("genre_id"))
            val title = row.optString("title", row.optString("name"))
            if (id.isBlank() || title.isBlank()) null else Category(id, title)
        }
    }

    fun channels(categoryId: String): List<Channel> {
        ensureToken()
        val params = mapOf("genre" to categoryId, "force_ch_link_check" to "", "fav" to "0",
            "sortby" to "number", "hd" to "0", "p" to "0")
        return rows(request("itv", "get_ordered_list", params, token)).mapNotNull { row ->
            val id = row.optString("id", row.optString("ch_id"))
            val name = row.optString("name", row.optString("title"))
            if (id.isBlank() || name.isBlank()) null else Channel(
                id = id,
                name = name,
                logo = row.optString("logo", row.optString("logo_30")),
                epgId = row.optString("xmltv_id", row.optString("epg_id")),
                command = row.optString("cmd")
            )
        }
    }

    fun stream(channel: Channel): String {
        ensureToken()
        val response = js(request("itv", "create_link", mapOf(
            "cmd" to channel.command.ifBlank { channel.id },
            "series" to "",
            "forced_storage" to "",
            "disable_ad" to "0",
            "download" to "0",
            "force_ch_link_check" to ""
        ), token))
        val raw = response.optString("cmd").ifBlank { response.optString("url") }
            .ifBlank { response.optString("link") }
        val cleaned = raw.trim().removePrefix("ffmpeg ").removePrefix("auto ").trim()
        val url = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(cleaned)?.value
            ?: cleaned.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        check(!url.isNullOrBlank()) { "The portal did not return a stream URL" }
        return url
    }

    fun playbackHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "X-User-Agent" to "Model: MAG250; Link: WiFi",
        "Cookie" to cookie
    ) + if (token.isNotBlank()) mapOf("Authorization" to "Bearer $token") else emptyMap()

    private fun ensureToken() {
        if (token.isNotBlank()) return
        verify()
    }

    private fun authorizeByCredentials() {
        require(login.isNotBlank() && password.isNotBlank()) { "Login and password are required for this authorization mode" }
        require(URL(apiUrl).protocol.equals("https", true)) {
            "Login/password authorization requires HTTPS so credentials are not sent in clear text"
        }
        val scriptName = if (authMode == 1) "auth_simple.php" else "auth_every_load.php"
        val scriptUrl = apiUrl.removeSuffix("load.php") + "tools/" + scriptName
        val connection = URL(scriptUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi")
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = Uri.Builder().appendQueryParameter("mac", mac).appendQueryParameter("login", login)
                .appendQueryParameter("password", password).build().encodedQuery
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "Login authorization HTTP $code" }
            check(!body.contains("invalid login", true) && !body.contains("wrong password", true) &&
                !body.contains("unauthorized", true)) { "Portal rejected the login or password" }
        } finally {
            connection.disconnect()
        }
    }

    private fun request(type: String, action: String, params: Map<String, String>, authToken: String?): JSONObject {
        val uri = Uri.parse(apiUrl).buildUpon()
            .appendQueryParameter("type", type)
            .appendQueryParameter("action", action)
            .apply {
                params.forEach { (key, value) -> appendQueryParameter(key, value) }
                appendQueryParameter("JsHttpRequest", "1-xml")
            }.build()
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi")
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Authorization", if (authToken.isNullOrBlank()) "MAC $mac" else "Bearer $authToken")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "Portal HTTP $code" }
            val text = body.trim().removePrefix(")]}'").trimStart(',', '\n', '\r', ' ')
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun js(response: JSONObject): JSONObject {
        val value = response.opt("js") ?: return response
        return when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrDefault(JSONObject())
            else -> JSONObject()
        }
    }

    private fun rows(response: JSONObject): List<JSONObject> {
        val envelope = js(response)
        val directData = envelope.opt("data")
        if (directData is JSONArray) return (0 until directData.length()).mapNotNull { directData.optJSONObject(it) }
        val array = envelope.optJSONArray("data") ?: envelope.optJSONArray("genres")
            ?: envelope.optJSONArray("channels") ?: response.optJSONArray("data")
            ?: response.optJSONArray("genres") ?: response.optJSONArray("channels") ?: JSONArray()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private val cookie: String
        get() = "mac=$mac; stb_lang=ru; timezone=Europe%2FBerlin"

    private val userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250"
}
