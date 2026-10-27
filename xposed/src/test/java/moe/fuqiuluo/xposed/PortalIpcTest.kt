package moe.fuqiuluo.xposed

import moe.fuqiuluo.xposed.utils.PortalIpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalIpcTest {
    @Test
    fun legacyAndV2UseDifferentProviderNamespaces() {
        assertEquals(
            PortalIpc.LEGACY_PROVIDER,
            PortalIpc.providerForApplicationId(PortalIpc.LEGACY_APPLICATION_ID),
        )
        assertEquals(
            PortalIpc.V2_PROVIDER,
            PortalIpc.providerForApplicationId(PortalIpc.V2_APPLICATION_ID),
        )
        assertFalse(PortalIpc.LEGACY_PROVIDER == PortalIpc.V2_PROVIDER)
    }

    @Test
    fun unknownApplicationAndProviderDoNotGainV2Access() {
        assertEquals(PortalIpc.LEGACY_PROVIDER, PortalIpc.providerForApplicationId("other.app"))
        assertNull(PortalIpc.applicationIdForProvider("unknown"))
        assertFalse(PortalIpc.isPortalProvider("unknown"))
        assertTrue(PortalIpc.isPortalProvider(PortalIpc.V2_PROVIDER))
        assertFalse(PortalIpc.isV2Provider(PortalIpc.LEGACY_PROVIDER))
        assertTrue(PortalIpc.isV2Provider(PortalIpc.V2_PROVIDER))
    }

    @Test
    fun dynamicLibraryLoadingAcceptsOnlyTheManagedPath() {
        assertTrue(RemoteCommandHandler.isAllowedLibraryPath("/data/local/portal-lib/libportal.so"))
        assertFalse(RemoteCommandHandler.isAllowedLibraryPath("/data/local/portal-lib/other.so"))
        assertFalse(RemoteCommandHandler.isAllowedLibraryPath("/tmp/libportal.so"))
    }

    @Test
    fun xiaomiFixedStartRequiresProxyOnlyAtSystemBoundary() {
        fun requires(command: String, forwarded: Boolean = false, enabled: Boolean = false,
                     fixed: Boolean = false, manufacturer: String = "Xiaomi"): Boolean =
            PortalIpc.requiresXiaomiFusedProxy(
                manufacturer, PortalIpc.V2_PROVIDER, command, enabled, fixed, forwarded,
            )

        assertTrue(requires(PortalIpc.FIXED_START_COMMAND))
        assertTrue(requires("update_location", enabled = true, fixed = true))
        assertFalse(requires(PortalIpc.FIXED_START_COMMAND, forwarded = true))
        assertFalse(requires("stop", enabled = true, fixed = true))
        assertFalse(requires("update_location", enabled = false, fixed = false))
        assertFalse(requires(PortalIpc.FIXED_START_COMMAND, manufacturer = "other"))
    }

    @Test
    fun runningV2FixedSessionRejectsMovementUntilStopped() {
        assertTrue(PortalIpc.rejectsFixedMovement(PortalIpc.V2_PROVIDER, "move", true, true))
        assertTrue(PortalIpc.rejectsFixedMovement(PortalIpc.V2_PROVIDER, "update_location", true, true))
        assertFalse(PortalIpc.rejectsFixedMovement(PortalIpc.V2_PROVIDER, "stop", true, true))
        assertFalse(PortalIpc.rejectsFixedMovement(PortalIpc.V2_PROVIDER, "update_location", false, false))
        assertFalse(PortalIpc.rejectsFixedMovement(PortalIpc.LEGACY_PROVIDER, "move", true, true))
    }

    @Test
    fun fixedStartOnXiaomiRequiresTelephonyProxyUnlessForwarded() {
        val start = PortalIpc.FIXED_START_COMMAND
        assertTrue(PortalIpc.requiresTelephonyProxy("Xiaomi", PortalIpc.V2_PROVIDER, start, forwarded = false))
        assertFalse(PortalIpc.requiresTelephonyProxy("Xiaomi", PortalIpc.V2_PROVIDER, start, forwarded = true))
        assertFalse(PortalIpc.requiresTelephonyProxy("Xiaomi", PortalIpc.V2_PROVIDER, "stop", forwarded = false))
        assertFalse(PortalIpc.requiresTelephonyProxy("Xiaomi", PortalIpc.LEGACY_PROVIDER, start, forwarded = false))
        assertFalse(PortalIpc.requiresTelephonyProxy("Google", PortalIpc.V2_PROVIDER, start, forwarded = false))
    }
}
