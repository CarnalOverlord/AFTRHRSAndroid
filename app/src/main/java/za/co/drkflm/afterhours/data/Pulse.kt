package za.co.aftrhrs.data

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

enum class PulseIntention(val wire: String, val title: String) {
    RIGHT_NOW("RIGHT_NOW", "Right now"), FEELING_NAUGHTY("FEELING_NAUGHTY", "Feeling naughty"),
    UP_FOR_SOMETHING("UP_FOR_SOMETHING", "Up for something"), LETS_TALK("LETS_TALK", "Let’s talk"),
    SOMETHING_MORE("SOMETHING_MORE", "Something more");
    companion object { fun from(value: String?): PulseIntention? = entries.firstOrNull { it.wire == value } }
}
enum class PulseVisibility(val wire: String, val title: String, val description: String) {
    GRID("GRID", "Grid", "Visible to eligible members discovering Pulse."),
    CONNECTIONS("CONNECTIONS", "Connections", "Visible only to your existing connections."),
    PRIVATE("PRIVATE", "Private", "Only you can see this Pulse; preferences can still be saved." );
    companion object { fun from(value: String?): PulseVisibility? = entries.firstOrNull { it.wire == value } }
}

data class PulsePreferences(val primaryIntent: PulseIntention?, val secondaryIntents: List<PulseIntention>, val message: String?, val visibility: PulseVisibility?, val durationMinutes: Int?)
data class PulseSession(val id: String?, val primaryIntent: PulseIntention, val secondaryIntents: List<PulseIntention>, val message: String?, val visibility: PulseVisibility, val createdAt: String?, val expiresAt: String, val updatedAt: String?)
data class PulseEntitlement(val canPublish: Boolean?, val source: String?, val accessExpiresAt: String?)
data class PulseMeResponse(val preferences: PulsePreferences?, val session: PulseSession?, val entitlement: PulseEntitlement?, val eligible: Boolean?, val serverTime: String?)
data class PulseSummary(val id: String, val userId: String?, val displayName: String?, val primaryIntent: PulseIntention, val secondaryIntents: List<PulseIntention>, val message: String?, val visibility: PulseVisibility?, val createdAt: String?, val expiresAt: String, val photoUrl: String? = null)
data class PulseDiscoverResponse(val items: List<PulseSummary>, val nextCursor: String?, val serverTime: String?)
data class PulseDetailResponse(val pulse: PulseSummary?, val serverTime: String?)
data class PulsePreferencesResponse(val preferences: PulsePreferences)
data class PulseRespondResult(val conversationId: String, val messageId: String?, val replayed: Boolean?)
data class PulseSavePayload(val primaryIntent: PulseIntention, val secondaryIntents: List<PulseIntention>, val message: String?, val visibility: PulseVisibility, val durationMinutes: Int) {
    fun json() = buildJsonObject { put("primaryIntent", primaryIntent.wire); put("secondaryIntents", JsonArray(secondaryIntents.distinct().filter { it != primaryIntent }.take(3).map { JsonPrimitive(it.wire) })); message?.trim()?.takeIf { it.isNotBlank() }?.let { put("message", it) }; put("visibility", visibility.wire); put("durationMinutes", durationMinutes) }
}
data class PulseResponsePayload(val pulseId: String, val idempotencyKey: String, val message: String) {
    fun json() = obj("pulseId" to pulseId, "idempotencyKey" to idempotencyKey, "message" to message)
}
data class PulseResponseSubmission(val message: String, val idempotencyKey: String) {
    companion object { fun forMessage(message: String) = PulseResponseSubmission(message, UUID.randomUUID().toString().lowercase()) }
    fun forUpdatedMessage(value: String) = if (value == message) this else forMessage(value)
}

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.stringList(key: String) = this[key]?.jsonArray?.mapNotNull { PulseIntention.from(it.jsonPrimitive.contentOrNull) }.orEmpty()
private fun parsePreferences(o: JsonObject?) = o?.let { PulsePreferences(PulseIntention.from(it.string("primaryIntent")), it.stringList("secondaryIntents"), it.string("message"), PulseVisibility.from(it.string("visibility")), it["durationMinutes"]?.jsonPrimitive?.intOrNull) }
private fun parseSession(o: JsonObject?) = o?.let { val primary = PulseIntention.from(it.string("primaryIntent")) ?: return@let null; val visibility = PulseVisibility.from(it.string("visibility")) ?: return@let null; val expiry = it.string("expiresAt") ?: return@let null; PulseSession(it.string("id"), primary, it.stringList("secondaryIntents"), it.string("message"), visibility, it.string("createdAt"), expiry, it.string("updatedAt")) }
private fun parseSummary(o: JsonObject): PulseSummary? { val id=o.string("id") ?: return null; val intent=PulseIntention.from(o.string("primaryIntent")) ?: return null; val expires=o.string("expiresAt") ?: return null; val profile=o["user"]?.jsonObject?.get("profile")?.jsonObject; return PulseSummary(id,o.string("userId"),o.string("displayName")?:profile?.string("displayName"),intent,o.stringList("secondaryIntents"),o.string("message"),PulseVisibility.from(o.string("visibility")),o.string("createdAt"),expires,o.string("photoUrl")) }
fun JsonElement.asPulseMe(): PulseMeResponse { val o=jsonObject; val ent=o["entitlement"]?.takeUnless { it is JsonNull }?.jsonObject; return PulseMeResponse(parsePreferences(o["preferences"]?.takeUnless { it is JsonNull }?.jsonObject),parseSession(o["session"]?.takeUnless { it is JsonNull }?.jsonObject),ent?.let { PulseEntitlement(it["canPublish"]?.jsonPrimitive?.booleanOrNull,it.string("source"),it.string("accessExpiresAt")) },o["eligible"]?.jsonPrimitive?.booleanOrNull,o.string("serverTime")) }
fun JsonElement.asPulsePreferences() = PulsePreferencesResponse(jsonObject["preferences"]?.jsonObject?.let(::parsePreferences) ?: error("Invalid Pulse preferences response"))
fun JsonElement.asPulseDiscover(): PulseDiscoverResponse { val o=jsonObject; return PulseDiscoverResponse(o["items"]?.jsonArray?.mapNotNull { parseSummary(it.jsonObject) }.orEmpty(),o.string("nextCursor"),o.string("serverTime")) }
fun JsonElement.asPulseDetail(): PulseDetailResponse { val o=jsonObject; return PulseDetailResponse(o["pulse"]?.takeUnless{it is JsonNull}?.jsonObject?.let(::parseSummary),o.string("serverTime")) }
fun JsonElement.asPulseRespond() = PulseRespondResult(jsonObject.string("conversationId") ?: error("Missing conversation"),jsonObject.string("messageId"),jsonObject["replayed"]?.jsonPrimitive?.booleanOrNull)
fun pulseRemainingSeconds(expiresAt: String, nowMillis: Long, serverOffsetMillis: Long): Long = runCatching { ((Instant.parse(expiresAt).toEpochMilli() - (nowMillis + serverOffsetMillis))/1000).coerceAtLeast(0) }.getOrDefault(0)

class PulseRepository {
    suspend fun me() = ApiClient.pulseRequest("me").asPulseMe()
    suspend fun save(payload: PulseSavePayload) = ApiClient.pulseRequest("me", HttpMethod.Put, payload.json()).asPulsePreferences()
    suspend fun publish(payload: PulseSavePayload) = ApiClient.pulseRequest("me", HttpMethod.Post, payload.json()).asPulseMe()
    suspend fun end() = ApiClient.pulseRequest("me", HttpMethod.Delete).asPulseMe()
    suspend fun discover(cursor: String?): PulseDiscoverResponse { val q = "discover?limit=20" + (cursor?.let { "&cursor=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")}" } ?: ""); return ApiClient.pulseRequest(q).asPulseDiscover() }
    suspend fun detail(id: String): PulseDetailResponse = ApiClient.pulseRequest("detail?id=${java.net.URLEncoder.encode(id, Charsets.UTF_8.name())}").asPulseDetail()
    suspend fun respond(payload: PulseResponsePayload) = ApiClient.pulseRequest("respond", HttpMethod.Post, payload.json()).asPulseRespond()
}

class PulsePagination {
    private val requested = mutableSetOf<String>()
    private var emptyPages = 0
    fun begin(cursor: String?) = cursor == null || requested.add(cursor)
    fun continueAfter(count: Int, next: String?, requestedCursor: String?): Boolean {
        if (next == null || next == requestedCursor || next in requested) return false
        emptyPages = if(count == 0) emptyPages + 1 else 0
        return count > 0 || emptyPages <= 10
    }
    fun reset() { requested.clear(); emptyPages=0 }
}
