package za.co.aftrhrs.data

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PulseTest {
    private val json=Json { ignoreUnknownKeys=true; isLenient=true }
    @Test fun parsesMeAndNullableSession() {
        val me=json.parseToJsonElement("""{"preferences":{"primaryIntent":"RIGHT_NOW","secondaryIntents":[],"visibility":"GRID","durationMinutes":60},"session":null,"entitlement":{"canPublish":true,"source":"HAPPY_HOUR"},"eligible":true,"serverTime":"2026-11-18T18:00:00Z","extra":true}""").asPulseMe()
        assertEquals(PulseIntention.RIGHT_NOW,me.preferences?.primaryIntent)
        assertNull(me.session);assertTrue(me.eligible==true);assertEquals("HAPPY_HOUR",me.entitlement?.source)
    }
    @Test fun decodesPutWrapperAndOpaqueCursor() {
        val prefs=json.parseToJsonElement("""{"preferences":{"primaryIntent":"LETS_TALK","secondaryIntents":[],"visibility":"PRIVATE","durationMinutes":30}}""").asPulsePreferences()
        assertEquals(PulseVisibility.PRIVATE,prefs.preferences.visibility)
        val page=json.parseToJsonElement("""{"items":[],"nextCursor":"opaque==","serverTime":"2026-11-18T18:00:00Z"}""").asPulseDiscover()
        assertEquals("opaque==",page.nextCursor)
    }
    @Test fun decodesDeployedPulseDirectSuccessAndErrorBodies() {
        val me=unwrapPulseResponse(200,"""{"preferences":null,"session":null,"eligible":true,"serverTime":"2026-11-19T08:00:00Z"}""")
        assertTrue(me.asPulseMe().eligible==true)

        val ineligible=runCatching { unwrapPulseResponse(403,"""{"error":"PULSE_INELIGIBLE"}""") }.exceptionOrNull() as ApiException
        assertEquals("PULSE_INELIGIBLE",ineligible.code)
        assertEquals(403,ineligible.status)

        val limited=runCatching { unwrapPulseResponse(429,"""{"error":"RATE_LIMITED","retryable":true}""",retryAfter=18) }.exceptionOrNull() as ApiException
        assertEquals("RATE_LIMITED",limited.code)
        assertEquals(18L,limited.retryAfterSeconds)
    }
    @Test fun serializesWireEnumsAndNormalizedPayload() {
        val json=PulseSavePayload(PulseIntention.LETS_TALK,listOf(PulseIntention.LETS_TALK,PulseIntention.RIGHT_NOW),"  hi  ",PulseVisibility.CONNECTIONS,120).json().jsonObject
        assertEquals("LETS_TALK",json["primaryIntent"]?.toString()?.trim('"'))
        assertEquals("hi",json["message"]?.toString()?.trim('"'))
        assertEquals(1,json["secondaryIntents"]!!.toString().split("RIGHT_NOW").size-1)
    }
    @Test fun paginationPreventsRepeatedCursorAndAllowsEmptyPageContinuation() {
        val p=PulsePagination();assertTrue(p.begin("one"));assertTrue(p.continueAfter(0,"two","one"));assertTrue(p.begin("two"));assertFalse(p.continueAfter(0,"two","two"));assertFalse(p.begin("two"))
    }
    @Test fun expiryUsesServerOffsetAndResponseKeyTracksLogicalText() {
        val now=Instant.parse("2026-11-18T18:00:00Z").toEpochMilli();assertEquals(60,pulseRemainingSeconds("2026-11-18T18:02:00Z",now,60_000))
        val first=PulseResponseSubmission.forMessage("Hello");assertSame(first,first.forUpdatedMessage("Hello"));assertNotEquals(first.idempotencyKey,first.forUpdatedMessage("Hello!").idempotencyKey)
    }
}
