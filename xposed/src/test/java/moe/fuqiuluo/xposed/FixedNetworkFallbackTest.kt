package moe.fuqiuluo.xposed

import moe.fuqiuluo.xposed.utils.FixedNetworkFallback
import moe.fuqiuluo.xposed.utils.FixedNetworkFallback.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixedNetworkFallbackTest {
    private data class Fix(val provider: String, val payload: String)

    private val eligible = Request(
        verifiedDevice = true,
        activeFixedSession = true,
        networkBlocked = true,
        provider = "network",
        callerFingerprint = "613a7b221944818e3f3546d425117acbcf57c004ebbb5df976f134ed471c79b1",
    )

    @Test
    fun `package fingerprint is stable and missing names stay absent`() {
        assertEquals(
            "bbae1aaae4410edd8257a63f535e6ce8bf43559afe523aa57c22ba775eb0aec4",
            FixedNetworkFallback.packageFingerprint("org.example.app"),
        )
        assertNull(FixedNetworkFallback.packageFingerprint(null))
        assertNull(FixedNetworkFallback.packageFingerprint(""))
    }

    @Test
    fun `approved GPS is sanitized before it becomes a network result`() {
        val source = Fix("gps", "private source")
        val fixed = Fix("network", "configured fixed point")
        var queryCount = 0
        val result = FixedNetworkFallback.resolve(
            eligible,
            queryApprovedGps = { queryCount++; source },
            sanitizeAsNetwork = { fixed },
        )

        assertEquals(1, queryCount)
        assertEquals(fixed, result)
    }

    @Test
    fun `unrelated or inactive requests cannot query or synthesize a fix`() {
        val ineligible = listOf(
            eligible.copy(verifiedDevice = false),
            eligible.copy(activeFixedSession = false),
            eligible.copy(networkBlocked = false),
            eligible.copy(provider = "gps"),
            eligible.copy(provider = null),
            eligible.copy(callerFingerprint = FixedNetworkFallback.packageFingerprint("org.example.app")),
            eligible.copy(callerFingerprint = null),
        )
        ineligible.forEach { request ->
            val result = FixedNetworkFallback.resolve<Fix>(
                request,
                queryApprovedGps = { error("must not query") },
                sanitizeAsNetwork = { error("must not sanitize") },
            )
            assertNull(result)
        }
    }

    @Test
    fun `a denied or empty platform GPS result stays empty`() {
        var sanitized = false
        val result = FixedNetworkFallback.resolve<Fix>(
            eligible,
            queryApprovedGps = { null },
            sanitizeAsNetwork = { sanitized = true; Fix("network", "fixed") },
        )
        assertNull(result)
        assertEquals(false, sanitized)
    }

    @Test
    fun `platform and sanitization failures never return the source location`() {
        val denied = FixedNetworkFallback.resolve<Fix>(
            eligible,
            queryApprovedGps = { throw SecurityException("denied") },
            sanitizeAsNetwork = { error("must not sanitize") },
        )
        assertNull(denied)

        val invalid = FixedNetworkFallback.resolve(
            eligible,
            queryApprovedGps = { Fix("gps", "private source") },
            sanitizeAsNetwork = { throw IllegalStateException("failed") },
        )
        assertNull(invalid)
    }
}
