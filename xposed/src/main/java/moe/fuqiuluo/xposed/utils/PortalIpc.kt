package moe.fuqiuluo.xposed.utils

/**
 * Names used by the command channel.  These are deliberately different from
 * the application package names: the location service exposes providers, not
 * application components.
 */
object PortalIpc {
    const val LEGACY_PROVIDER = "portal"
    const val V2_PROVIDER = "portal2026v2"

    const val LEGACY_APPLICATION_ID = "moe.fuqiuluo.portal"
    const val V2_APPLICATION_ID = "moe.fuqiuluo.portal2026v2"

    const val FIXED_START_COMMAND = "start_fixed"
    const val FIXED_START_ACK = "fixed_start_applied"
    const val FIXED_CONFIG_VALID = "fixed_config_valid"

    /** Set on a refused fixed start to name the missing protection. */
    const val FIXED_START_BLOCKED_REASON = "fixed_start_blocked_reason"
    const val BLOCKED_XIAOMI_FUSED = "xiaomi_fused"
    const val BLOCKED_TELEPHONY = "telephony"
    const val TELEPHONY_ROLE = "TelephonyHook"

    fun providerForApplicationId(applicationId: String?): String {
        return if (applicationId == V2_APPLICATION_ID) {
            V2_PROVIDER
        } else {
            LEGACY_PROVIDER
        }
    }

    fun applicationIdForProvider(provider: String): String? {
        return when (provider) {
            LEGACY_PROVIDER -> LEGACY_APPLICATION_ID
            V2_PROVIDER -> V2_APPLICATION_ID
            else -> null
        }
    }

    fun isPortalProvider(provider: String?): Boolean {
        return provider == LEGACY_PROVIDER || provider == V2_PROVIDER
    }

    /** This APK's system hook serves only its own control channel. */
    fun isV2Provider(provider: String?): Boolean = provider == V2_PROVIDER

    fun requiresXiaomiFusedProxy(
        manufacturer: String?,
        provider: String,
        command: String,
        enabled: Boolean,
        fixedMode: Boolean,
        forwarded: Boolean,
    ): Boolean {
        return !forwarded && provider == V2_PROVIDER &&
            manufacturer.equals("Xiaomi", ignoreCase = true) &&
            (command == FIXED_START_COMMAND || (command == "update_location" && enabled && fixedMode))
    }

    /**
     * Cell-tower hiding runs in com.android.phone and only follows the session
     * through its registered proxy. Without it, apps would see real cells.
     */
    fun requiresTelephonyProxy(
        manufacturer: String?,
        provider: String,
        command: String,
        forwarded: Boolean,
    ): Boolean {
        return !forwarded && provider == V2_PROVIDER &&
            manufacturer.equals("Xiaomi", ignoreCase = true) &&
            command == FIXED_START_COMMAND
    }

    fun rejectsFixedMovement(provider: String, command: String, enabled: Boolean, fixedMode: Boolean): Boolean {
        return provider == V2_PROVIDER && enabled && fixedMode &&
            command in setOf("update_location", "move")
    }
}
