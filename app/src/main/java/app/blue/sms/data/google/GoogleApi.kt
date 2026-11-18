package app.blue.sms.data.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

class GoogleApiException(message: String) : Exception(message)

data class DriveFile(val id: String, val name: String, val modifiedTime: String?, val size: Long?)

/** Small REST client for the two calls Blue needs: a Drive file and new Sheets. */
@Singleton
class GoogleApi @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun findFile(token: String, name: String): DriveFile? {
        val q = URLEncoder.encode("name = '$name' and trashed = false", "UTF-8")
        val res = call(token, "GET", "https://www.googleapis.com/drive/v3/files?q=$q&spaces=drive&orderBy=modifiedTime%20desc&fields=files(id,name,modifiedTime,size)")
        val files = json.parseToJsonElement(res.decodeToString()).jsonObject["files"]?.jsonArray ?: return null
        return files.firstOrNull()?.jsonObject?.let {
            DriveFile(
                it.str("id")!!, it.str("name") ?: name, it.str("modifiedTime"), it.str("size")?.toLongOrNull(),
            )
        }
    }

    /** Creates the file, or replaces its contents if [existingId] is given. Returns the file id. */
    suspend fun saveFile(token: String, name: String, bytes: ByteArray, existingId: String?): String {
        if (existingId != null) {
            call(token, "PATCH", "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media", bytes, "application/json")
            return existingId
        }
        val boundary = "blue${System.currentTimeMillis()}"
        val meta = buildJsonObject { put("name", name); put("mimeType", "application/json") }.toString()
        val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n").toByteArray() + bytes + "\r\n--$boundary--".toByteArray()
        val res = call(token, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
        return json.parseToJsonElement(res.decodeToString()).jsonObject.str("id") ?: throw GoogleApiException("Drive gave no file id")
    }

    suspend fun download(token: String, id: String): ByteArray =
        call(token, "GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media")

    /** Creates a new spreadsheet with one tab and writes [rows] (first row = header). Returns its URL. */
    suspend fun createSheet(token: String, title: String, tab: String, rows: List<List<Any?>>): String {
        val create = buildJsonObject {
            putJsonObject("properties") { put("title", title) }
            putJsonArray("sheets") {
                add(buildJsonObject {
                    putJsonObject("properties") {
                        put("title", tab)
                        putJsonObject("gridProperties") { put("frozenRowCount", 1) }
                    }
                })
            }
        }.toString()
        val res = json.parseToJsonElement(
            call(token, "POST", "https://sheets.googleapis.com/v4/spreadsheets", create.toByteArray(), "application/json").decodeToString()
        ).jsonObject
        val id = res.str("spreadsheetId") ?: throw GoogleApiException("Sheets gave no id")
        val values = buildJsonObject {
            put("values", JsonArray(rows.map { r -> JsonArray(r.map(::cell)) }))
        }.toString()
        val range = URLEncoder.encode("$tab!A1", "UTF-8")
        call(token, "PUT", "https://sheets.googleapis.com/v4/spreadsheets/$id/values/$range?valueInputOption=RAW", values.toByteArray(), "application/json")
        return res.str("spreadsheetUrl") ?: "https://docs.google.com/spreadsheets/d/$id"
    }

    private fun cell(v: Any?): JsonElement = when (v) {
        null -> JsonPrimitive("")
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(if (v) "yes" else "no")
        else -> JsonPrimitive(v.toString())
    }

    private suspend fun call(token: String, method: String, url: String, body: ByteArray? = null, type: String? = null): ByteArray =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                // HttpURLConnection has no PATCH; Google accepts the override header.
                requestMethod = if (method == "PATCH") "POST" else method
                if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", type ?: "application/json")
                    setFixedLengthStreamingMode(body.size)
                }
            }
            try {
                body?.let { b -> conn.outputStream.use { it.write(b) } }
                val code = conn.responseCode
                val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                if (code !in 200..299) {
                    val msg = runCatching {
                        json.parseToJsonElement(bytes.decodeToString()).jsonObject["error"]?.jsonObject?.str("message")
                    }.getOrNull()
                    throw GoogleApiException("Google ($code): ${msg ?: "request failed"}")
                }
                bytes
            } finally {
                conn.disconnect()
            }
        }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
