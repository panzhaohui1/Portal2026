@file:Suppress("LocalVariableName", "PrivateApi")
package moe.fuqiuluo.xposed.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Build
import android.os.Process


object BinderUtils {
    private val privacySensitiveSystemPackages = setOf(
        "com.xiaomi.location.fused",
        "com.xiaomi.metoknlp",
        "com.android.location.fused",
    )

    private fun getActivityContext(): Context? {
        // public static ActivityManagerService self()
        // frameworks/base/services/java/com/android/server/am/ActivityManagerService.java
        try {
            val cam = Class.forName("com.android.server.am.ActivityManagerService")
            val am = cam.getMethod("self").invoke(null) ?: return null
            val mContext = cam.getDeclaredField("mContext")
            mContext.isAccessible = true
            return mContext.get(am) as? Context
        } catch (e: Throwable) {
            return null
        }
    }

    fun getSystemContext(): Context? {
        try {
            val cActivityThread = Class.forName("android.app.ActivityThread")
            val activityThread = cActivityThread.getMethod("currentActivityThread")
                .invoke(null) ?: return null
            return (cActivityThread.getMethod("getSystemContext").invoke(activityThread) as? Context) ?: getActivityContext()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return null
    }

    fun getUidPackageNames(context: Context? = getSystemContext(), uid: Int = getCallerUid()): Array<String>? {
        if (context == null || uid < 0) return null
        return kotlin.runCatching {
            context.packageManager.getPackagesForUid(uid)
        }.getOrNull()
    }

    fun getCallerUid(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            kotlin.runCatching { Binder.getCallingUidOrThrow() }.getOrNull() ?: -1
        } else {
            // On older releases getCallingUid() may return this process UID
            // outside a transaction. Treat same-process Binder identity as
            // "no inbound caller" instead of using it for authorization.
            if (Binder.getCallingPid() == Process.myPid()) -1 else Binder.getCallingUid()
        }
    }

    /** The UID of this process; never substitutes for an inbound Binder caller. */
    fun getProcessUid(): Int = Process.myUid()

    /**
     * Check whether the `PortalService` is started properly
     */
    fun isLocationProviderEnabled(
        uid: Int = getCallerUid(),
        provider: String = PortalIpc.LEGACY_PROVIDER,
    ): Boolean {
        // System services are allowed to bridge the command to the provider,
        // but an unknown/negative UID is never treated as a system caller.
        if (isSystemIdentityCall(uid)) return true

        val expectedPackage = PortalIpc.applicationIdForProvider(provider)
        val packageNames = getUidPackageNames(uid = uid)
        val isAuthorized = expectedPackage != null &&
                packageNames?.contains(expectedPackage) == true &&
                packageNames.all { it == expectedPackage }
        if (isAuthorized) return true

        Logger.warn(
            "Rejected location provider caller: uid=$uid, provider=$provider, " +
                    "packageName=${packageNames?.joinToString()}",
        )
        return false
    }

    /**
     * Check exact package identities against PackageManager flags. This keeps
     * fused packages from being accepted/rejected by substring heuristics.
     */
    fun isSystemPackages(packageNames: String): Boolean {
        val names = packageNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return names.isNotEmpty() && names.all {
            it !in privacySensitiveSystemPackages && isSystemPackage(it)
        }
    }

    fun isSystemAppsCall(uid: Int = getCallerUid()): Boolean {
        if (uid < 0) return false
        if (uid < 10000) return true
        val packageNames = getUidPackageNames(uid = uid) ?: return false
        return packageNames.isNotEmpty() && packageNames.all {
            it !in privacySensitiveSystemPackages && isSystemPackage(it)
        }
    }

    /**
     * Apps outside the system uid range that still need real Wi-Fi/cell data while a
     * session runs (status bar, Wi-Fi picker). Preinstalled vendor apps are deliberately
     * not exempt: many of them upload Wi-Fi/cell observations to cloud location services.
     */
    private val privacyExemptPackages = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.networkstack",
        "com.android.networkstack.tethering",
    )

    /** Exemption for Wi-Fi / cell privacy hooks; narrower than [isSystemAppsCall]. */
    fun isPrivacyExemptCall(uid: Int = getCallerUid()): Boolean {
        if (uid < 0) return false
        if (uid < 10000) return true
        val packageNames = getUidPackageNames(uid = uid) ?: return false
        return packageNames.isNotEmpty() && packageNames.all { it in privacyExemptPackages }
    }

    /** Package-name variant of [isPrivacyExemptCall] for hooks that receive the caller package. */
    fun isPrivacyExemptPackages(packageNames: String): Boolean {
        val names = packageNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return names.isNotEmpty() && names.all { it in privacyExemptPackages }
    }

    /** Strict system-identity check used for authenticated IPC. */
    fun isSystemIdentityCall(uid: Int = getCallerUid()): Boolean {
        if (uid < 0) return false
        if (uid < 10000) return true
        val packageNames = getUidPackageNames(uid = uid) ?: return false
        return packageNames.isNotEmpty() && packageNames.all { isSystemPackage(it) }
    }

    /** Explicit process-identity variant for startup code outside Binder. */
    fun isSystemIdentityProcess(): Boolean = isSystemIdentityCall(getProcessUid())

    /** Retained for existing privacy hooks; it deliberately keeps the fused denylist. */
    fun isSystemAppsProcess(): Boolean = isSystemAppsCall(getProcessUid())

    private fun isSystemPackage(packageName: String): Boolean {
        val context = getSystemContext() ?: return false
        return kotlin.runCatching {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            applicationInfo.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
    }
}
