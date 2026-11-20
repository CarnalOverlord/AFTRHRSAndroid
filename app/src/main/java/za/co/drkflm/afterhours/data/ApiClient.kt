package za.co.aftrhrs.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.*
import java.io.File
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import coil3.network.NetworkHeaders

class ApiException(message: String, val code: String? = null, val status: Int? = null, val retryAfterSeconds: Long? = null) : Exception(message)

object ApiClient {
    const val ORIGIN = "https://app.aftrhrs.co.za"
    private const val BASE = "$ORIGIN/api/v1/"
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val client = HttpClient(OkHttp) {
        engine { preconfigured = Network.http }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 30_000 }
        expectSuccess = false
        defaultRequest {
            accept(ContentType.Application.Json)
            header("X-AFTRHRS-Client", "AFTRHRS-Native-Android/1.0")
        }
    }

    suspend fun request(path: String, method: HttpMethod = HttpMethod.Get, body: JsonElement? = null): JsonElement {
        val response = client.request(BASE + path) {
            this.method = method
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return unwrap(response.status.value, response.body<String>(), response.headers["Retry-After"]?.toLongOrNull())
    }

    /** Pulse is a backend feature router outside the legacy /api/v1 namespace. */
    suspend fun pulseRequest(path: String, method: HttpMethod = HttpMethod.Get, body: JsonElement? = null): JsonElement {
        val response = client.request("$ORIGIN/api/pulse/$path") {
            this.method = method
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return unwrapPulseResponse(response.status.value, response.body<String>(), response.headers["Retry-After"]?.toLongOrNull())
    }

    /** Premium Bucket List lives outside the legacy v1 router. No trailing slash on mutations. */
    suspend fun bucketListRequest(path: String = "", method: HttpMethod = HttpMethod.Get, body: JsonElement? = null): JsonElement {
        val endpoint = "$ORIGIN/api/bucket-list" + when {
            path.isBlank() -> ""
            path.startsWith("?") || path.startsWith("/") -> path
            else -> "/$path"
        }
        val response = client.request(endpoint) {
            this.method = method
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return unwrap(response.status.value, response.body<String>(), response.headers["Retry-After"]?.toLongOrNull())
    }

    suspend fun linkPreview(url: String): JsonObject? {
        val response = client.get("$ORIGIN/api/link-preview") { parameter("url", url) }
        if (response.status.value !in 200..299) return null
        val raw = response.body<String>()
        if (raw.isBlank() || raw == "null") return null
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    suspend fun upload(path: String, file: File, field: String = "image", fields: Map<String, String> = emptyMap()): JsonElement =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val mediaType = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                else -> "image/jpeg"
            }.toMediaType()
            val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (key, value) -> addFormDataPart(key, value) }
                addFormDataPart(field, file.name, file.readBytes().toRequestBody(mediaType))
            }.build()
            val request = Request.Builder()
                .url(BASE + path)
                .post(form)
                .header("Accept", "application/json")
                .header("X-AFTRHRS-Client", "AFTRHRS-Native-Android/1.0")
                .build()
            Network.http.newCall(request).execute().use { response ->
                unwrap(response.code, response.body?.string().orEmpty())
            }
        }

    suspend fun openOnce(conversationId: String, messageId: String): Pair<ByteArray, String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = Request.Builder()
                .url(BASE + "conversations/$conversationId/messages/$messageId/open")
                .get()
                .header("X-AFTRHRS-Client", "AFTRHRS-Native-Android/1.0")
                .header("Cache-Control", "no-store")
                .build()
            Network.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    val message = runCatching {
                        json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                            ?.get("message")?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: "This Quickie has already been opened or is unavailable."
                    throw ApiException(message)
                }
                (response.body?.bytes() ?: error("Media unavailable")) to
                    (response.header("Content-Type") ?: "image/webp")
            }
        }

    suspend fun groupMedia(groupId: String, messageId: String): Pair<ByteArray, String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = Request.Builder()
                .url(BASE + "group-chats/$groupId/media/$messageId")
                .get()
                .header("X-AFTRHRS-Client", "AFTRHRS-Native-Android/1.0")
                .header("Cache-Control", "no-store")
                .build()
            Network.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    val message = runCatching {
                        json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                            ?.get("message")?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: "This group media is unavailable."
                    throw ApiException(message)
                }
                (response.body?.bytes() ?: error("Media unavailable")) to
                    (response.header("Content-Type") ?: "image/webp")
            }
        }

    fun imageHeaders(): NetworkHeaders = NetworkHeaders.Builder().apply {
        Network.cookieHeader().takeIf { it.isNotBlank() }?.let { set("Cookie", it) }
        set("X-AFTRHRS-Client", "AFTRHRS-Native-Android/1.0")
    }.build()

    private fun unwrap(status: Int, raw: String, retryAfter: Long? = null): JsonElement {
        val envelope = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            val normalized = raw.trimStart().lowercase()
            if (status >= 500 || normalized.startsWith("<!doctype html") || normalized.startsWith("<html") || normalized.contains("502 bad gateway")) {
                throw ApiException("AFTRHRS is temporarily unavailable. Please try again shortly.")
            }
            throw ApiException("The server returned an unexpected response. Please try again shortly.")
        }
        val ok = envelope["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (status !in 200..299 || !ok) {
            val error = envelope["error"]?.jsonObject
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Request failed"
            val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: error?.get("error")?.jsonPrimitive?.contentOrNull
            throw ApiException(message, code, status, retryAfter ?: error?.get("retryAfter")?.jsonPrimitive?.longOrNull)
        }
        return envelope["data"] ?: JsonNull
    }

    fun mediaUrl(value: String?): String? = value?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "$ORIGIN${if (it.startsWith('/')) "" else "/"}$it"
    }
}

/** Pulse uses direct JSON bodies and `{ error: "CODE" }`, unlike the legacy API envelope. */
internal fun unwrapPulseResponse(status: Int, raw: String, retryAfter: Long? = null): JsonElement {
    val body = runCatching { Json.parseToJsonElement(raw) }.getOrElse {
        if (status >= 500) throw ApiException("AFTRHRS is temporarily unavailable. Please try again shortly.", status = status)
        throw ApiException("The Pulse server returned an unexpected response.", status = status)
    }
    if (status !in 200..299) {
        val error = (body as? JsonObject)?.get("error")
        val errorObject = error as? JsonObject
        val code = error?.let { value ->
            (value as? JsonPrimitive)?.contentOrNull
                ?: errorObject?.get("code")?.jsonPrimitive?.contentOrNull
                ?: errorObject?.get("error")?.jsonPrimitive?.contentOrNull
        }
        val message = errorObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: code
            ?: "Pulse request failed. Please try again."
        val bodyRetry = (body as? JsonObject)?.get("retryAfterSeconds")?.jsonPrimitive?.longOrNull
            ?: errorObject?.get("retryAfter")?.jsonPrimitive?.longOrNull
        throw ApiException(message, code, status, retryAfter ?: bodyRetry)
    }
    return body
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> when (v) {
        null -> put(k, JsonNull); is String -> put(k, v); is Boolean -> put(k, v); is Int -> put(k, v)
        is Double -> put(k, v); is JsonElement -> put(k, v); is List<*> -> put(k, buildJsonArray { v.forEach { add(it.toString()) } })
        else -> put(k, v.toString())
    } }
}
fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
fun JsonObject.strOrNull(key: String) = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false
fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0
fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
fun JsonObject.objectOrNull(key: String) = this[key] as? JsonObject
