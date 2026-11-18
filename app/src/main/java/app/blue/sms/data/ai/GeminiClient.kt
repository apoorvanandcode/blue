package app.blue.sms.data.ai

import app.blue.sms.data.prefs.SettingsRepository
import app.blue.sms.data.secure.SecretStore
import app.blue.sms.domain.ai.AiExtractor
import app.blue.sms.domain.ai.AiOutcome
import app.blue.sms.domain.ai.AiReplyParser
import app.blue.sms.domain.ai.AiSchema
import app.blue.sms.domain.ai.FailKind
import app.blue.sms.domain.ai.InvalidAiReply
import app.blue.sms.domain.parse.Otp
import app.blue.sms.domain.parse.SmsInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends exactly one SMS (sender + text) per request to the Gemini Interactions API.
 * Asks Google not to store it (store=false), uses minimal thinking, and requires JSON
 * matching [AiSchema]. OTP-like SMS are refused here as a second guard.
 */
@Singleton
class GeminiClient @Inject constructor(
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
) : AiExtractor {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun extract(sms: SmsInput): AiOutcome = withContext(Dispatchers.IO) {
        if (Otp.looksLikeOtp(sms.body)) return@withContext AiOutcome.Failed("OTP SMS are never sent to Gemini", FailKind.THIS_SMS)
        val key = secrets.geminiKey() ?: return@withContext AiOutcome.Failed("No Gemini API key in Settings", FailKind.SETUP)
        val model = settings.settings.first().geminiModel
        val received = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(sms.receivedAt).atZone(zone))
        val body = buildJsonObject {
            put("model", model)
            put("system_instruction", AiSchema.instructions)
            put("input", "Sender: ${sms.sender}\nReceived: $received (India time)\nSMS:\n${sms.body}")
            put("store", false)
            putJsonObject("generation_config") { put("thinking_level", "minimal") }
            putJsonObject("response_format") {
                put("type", "text")
                put("mime_type", "application/json")
                put("schema", AiSchema.schema)
            }
        }.toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching {
                    // Errors come as {"error":{...}} or [{"error":{...}}].
                    val el = json.parseToJsonElement(text)
                    val obj = (el as? JsonArray)?.firstOrNull() as? JsonObject ?: el as? JsonObject
                    obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.replace(key, "…")?.take(160) ?: "HTTP $code"
                val kind = if (code == 429 || code >= 500) FailKind.TEMPORARY else FailKind.SETUP
                return@withContext AiOutcome.Failed("Gemini: $msg", kind)
            }
            val output = modelText(text) ?: return@withContext AiOutcome.Failed("Gemini returned no text", FailKind.THIS_SMS)
            AiOutcome.Ok(AiReplyParser.parse(output, sms, zone))
        } catch (e: InvalidAiReply) {
            AiOutcome.Failed("Gemini reply was invalid: ${e.message}", FailKind.THIS_SMS)
        } catch (e: SocketTimeoutException) {
            AiOutcome.Failed("Gemini timed out", FailKind.TEMPORARY)
        } catch (e: IOException) {
            AiOutcome.Failed("No connection to Gemini", FailKind.TEMPORARY)
        } finally {
            conn.disconnect()
        }
    }

    /** steps[] -> type "model_output" -> content[] -> type "text". */
    private fun modelText(response: String): String? = runCatching {
        val root = json.parseToJsonElement(response).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && status != "completed") return null
        (root["steps"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "model_output" }
            ?.flatMap { (it["content"] as? JsonArray)?.mapNotNull { c -> c as? JsonObject } ?: emptyList() }
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull ?: "" }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    }
}
