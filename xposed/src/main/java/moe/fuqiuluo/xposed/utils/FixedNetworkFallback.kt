package moe.fuqiuluo.xposed.utils

import java.security.MessageDigest

/** A narrowly scoped compatibility path for a verified caller. */
internal object FixedNetworkFallback {
    // A fingerprint limits compatibility scope; it is not an authentication mechanism.
    private const val ALLOWED_CALLER_FINGERPRINT =
        "613a7b221944818e3f3546d425117acbcf57c004ebbb5df976f134ed471c79b1"

    internal data class Request(
        val verifiedDevice: Boolean,
        val activeFixedSession: Boolean,
        val networkBlocked: Boolean,
        val provider: String?,
        val callerFingerprint: String?,
    )

    internal fun packageFingerprint(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(packageName.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.getOrNull()
    }

    /**
     * A GPS result is only proof that the platform allowed this caller and has
     * a cache. The sanitizer must construct a new fix from configured state;
     * the platform's location object is deliberately not passed to it.
     */
    internal fun <T : Any> resolve(
        request: Request,
        queryApprovedGps: () -> T?,
        sanitizeAsNetwork: () -> T,
    ): T? {
        if (!request.verifiedDevice || !request.activeFixedSession || !request.networkBlocked ||
            request.provider != "network" || request.callerFingerprint != ALLOWED_CALLER_FINGERPRINT
        ) return null

        return runCatching {
            if (queryApprovedGps() == null) null else sanitizeAsNetwork()
        }.getOrNull()
    }
}
