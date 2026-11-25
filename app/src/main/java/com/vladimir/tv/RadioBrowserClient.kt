package com.vladimir.tv

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class RadioStation(
    val uuid: String,
    val name: String,
    val streamUrl: String,
    val country: String,
    val language: String,
    val bitrate: Int,
    val codec: String,
    val logo: String
)

internal object RadioBrowserClient {
    private const val api = "https://de1.api.radio-browser.info/json"

    fun stations(countryCode: String, limit: Int = 80): List<RadioStation> {
        val url = "$api/stations/bycountrycodeexact/${URLEncoder.encode(countryCode, "UTF-8")}" +
            "?limit=${limit.coerceIn(1, 100)}&hidebroken=true&order=clickcount&reverse=true"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "VladiMir-TV-Online/3.0")
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode in 200..299) { "Radio directory HTTP ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val rows = JSONArray(body)
            return (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val stream = row.optString("url_resolved").ifBlank { row.optString("url") }
                val name = row.optString("name").trim()
                if (name.isBlank() || !(stream.startsWith("https://") || stream.startsWith("http://"))) null
                else RadioStation(row.optString("stationuuid"), name, stream,
                    row.optString("country"), row.optString("language"), row.optInt("bitrate"),
                    row.optString("codec"), row.optString("favicon"))
            }
        } finally {
            connection.disconnect()
        }
    }

    fun musicStations(tag: String? = null, name: String? = null, limit: Int = 80): List<RadioStation> {
        val params = mutableListOf("limit=${limit.coerceIn(1, 100)}", "hidebroken=true", "order=clickcount", "reverse=true")
        tag?.trim()?.takeIf { it.isNotBlank() }?.let { params += "tag=${URLEncoder.encode(it, "UTF-8")}" }
        name?.trim()?.takeIf { it.isNotBlank() }?.let { params += "name=${URLEncoder.encode(it, "UTF-8")}" }
        val connection = URL("$api/stations/search?${params.joinToString("&")}").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "VladiMir-TV-Online/3.0")
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode in 200..299) { "Radio directory HTTP ${connection.responseCode}" }
            val rows = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            return (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val stream = row.optString("url_resolved").ifBlank { row.optString("url") }
                val stationName = row.optString("name").trim()
                if (stationName.isBlank() || !(stream.startsWith("https://") || stream.startsWith("http://"))) null
                else RadioStation(row.optString("stationuuid"), stationName, stream,
                    row.optString("country"), row.optString("language"), row.optInt("bitrate"),
                    row.optString("codec"), row.optString("favicon"))
            }
        } finally {
            connection.disconnect()
        }
    }

    fun registerClick(uuid: String) {
        if (uuid.isBlank()) return
        runCatching {
            (URL("$api/url/${URLEncoder.encode(uuid, "UTF-8")}").openConnection() as HttpURLConnection).run {
                connectTimeout = 3_000
                readTimeout = 3_000
                setRequestProperty("User-Agent", "VladiMir-TV-Online/3.0")
                inputStream.close()
                disconnect()
            }
        }
    }
}
