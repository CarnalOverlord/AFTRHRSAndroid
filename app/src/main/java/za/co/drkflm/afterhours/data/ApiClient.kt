package za.co.drkflm.afterhours.data

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
import coil3.network.NetworkHeaders

class ApiException(message: String) : Exception(message)

object ApiClient {
    const val ORIGIN = "https://afterhours.drkflm.co.za"
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
        return unwrap(response.status.value, response.body<String>())
    }

    suspend fun linkPreview(url: String): JsonObject? {
        val response = client.get("$ORIGIN/api/link-preview") { parameter("url", url) }
        if (response.status.value !in 200..299) return null
        val raw = response.body<String>()
        if (raw.isBlank() || raw == "null") return null
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    suspend fun upload(path: String, file: File, field: String = "image", fields: Map<String, String> = emptyMap()): JsonElement {
        val response = client.submitFormWithBinaryData(BASE + path, formData {
            fields.forEach { (key, value) -> append(key, value) }
            append(field, file.readBytes(), Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"")
            })
        })
        return unwrap(response.status.value, response.body<String>())
    }

    fun imageHeaders(): NetworkHeaders = NetworkHeaders.Builder().apply {
        Network.cookieHeader().takeIf { it.isNotBlank() }?.let { set("Cookie", it) }
        set("X-AFTRHRS-Client", "AFTRHRS-Native-Android/1.0")
    }.build()

    private fun unwrap(status: Int, raw: String): JsonElement {
        val envelope = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw ApiException("The server returned an unreadable response.")
        }
        val ok = envelope["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (status !in 200..299 || !ok) {
            val message = envelope["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Request failed"
            throw ApiException(message)
        }
        return envelope["data"] ?: JsonNull
    }

    fun mediaUrl(value: String?): String? = value?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "$ORIGIN${if (it.startsWith('/')) "" else "/"}$it"
    }
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
