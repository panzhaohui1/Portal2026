package moe.fuqiuluo.xposed.hooks.provider

import android.app.ActivityManager
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.FixedGpsSession
import moe.fuqiuluo.xposed.utils.Logger
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Experimental fixed GPS and fused reports for the verified Android 13 Mi 10.
 * Reports enter LocationProviderManager, never an application's Binder listener,
 * so the platform continues to own listener eligibility and AppOps checks.
 */
internal object FixedGpsDispatcher : BaseLocationHook() {
    private val session = FixedGpsSession()
    private val managersLock = Any()
    private val managers = WeakHashMap<Any, Boolean>()
    private var executor: ScheduledExecutorService? = null
    private var reportedAttempt = false

    fun isVerifiedTestDevice(): Boolean =
        Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU &&
            Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) && Build.DEVICE == "umi"

    fun hasRequiredSystemInterfaces(managerClass: Class<*>, classLoader: ClassLoader): Boolean = runCatching {
        val resultClass = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
            ?: return@runCatching false
        val wrap = XposedHelpers.findMethodExactIfExists(resultClass, "wrap", List::class.java)
            ?: return@runCatching false
        val locations = XposedHelpers.findFieldIfExists(resultClass, "mLocations")
            ?: return@runCatching false
        val lock = XposedHelpers.findFieldIfExists(managerClass, "mLock")
            ?: return@runCatching false
        val state = XposedHelpers.findFieldIfExists(managerClass, "mState")
            ?: return@runCatching false
        val started = XposedHelpers.findFieldIfExists(managerClass, "STATE_STARTED")
            ?: return@runCatching false
        val user = XposedHelpers.findMethodExactIfExists(ActivityManager::class.java, "getCurrentUser")
            ?: return@runCatching false
        val name = XposedHelpers.findMethodExactIfExists(managerClass, "getName")
            ?: return@runCatching false
        val provider = XposedHelpers.findMethodExactIfExists(managerClass, "hasProvider")
            ?: return@runCatching false
        val enabled = XposedHelpers.findMethodExactIfExists(managerClass, "isEnabled", Int::class.javaPrimitiveType)
            ?: return@runCatching false
        Modifier.isStatic(wrap.modifiers) && wrap.returnType == resultClass &&
            List::class.java.isAssignableFrom(locations.type) && lock.type == Any::class.java &&
            state.type == Int::class.javaPrimitiveType && started.type == Int::class.javaPrimitiveType &&
            Modifier.isStatic(started.modifiers) && Modifier.isStatic(user.modifiers) &&
            user.returnType == Int::class.javaPrimitiveType && name.returnType == String::class.java &&
            provider.returnType == Boolean::class.javaPrimitiveType &&
            enabled.returnType == Boolean::class.javaPrimitiveType
    }.getOrDefault(false)

    fun begin(configure: () -> Unit): Boolean {
        if (!session.begin(configure)) return false
        if (isVerifiedTestDevice()) {
            session.runIfActive {
                synchronized(managersLock) {
                    if (managers.isNotEmpty()) ensureWorkerLocked()
                }
            }
        }
        return true
    }

    /** Disables new reports and waits a bounded time for an in-flight report. */
    fun stop(disable: () -> Unit): Boolean {
        val stopped = session.stop(5_000) {
            synchronized(managersLock) {
                executor?.shutdown()
                executor = null
                reportedAttempt = false
            }
            disable()
        }
        if (!stopped) {
            synchronized(managersLock) {
                executor?.shutdown()
                executor = null
            }
            Logger.warn("Fixed GPS report did not stop within the timeout")
        }
        return stopped
    }

    /** Keep a compatibility lookup inside the same stop barrier as reports. */
    fun <T> withActiveFixedSession(block: () -> T?): T? {
        var value: T? = null
        session.runIfActive {
            if (FakeLoc.enable && FakeLoc.fixedLocationMode) value = block()
        }
        return value
    }

    /** Prime fixed caches before a target app can request its first location. */
    fun primeFromSystemService(locationService: Any) {
        if (!isVerifiedTestDevice()) return
        listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER).forEach { provider ->
            runCatching {
                val manager = XposedHelpers.callMethod(
                    locationService, "getLocationProviderManager", provider,
                ) ?: return@runCatching
                if (supportedProvider(manager) != provider) return@runCatching
                session.runIfActive {
                    if (FakeLoc.enable && FakeLoc.fixedLocationMode) report(manager)
                }
            }.onFailure {
                Logger.warn("Unable to prime fixed location cache")
            }
        }
    }

    /** Remember successful framework registrations even before a session starts. */
    fun watch(manager: Any) {
        if (!isVerifiedTestDevice() || supportedProvider(manager) == null) return
        runCatching {
            synchronized(managersLock) { managers[manager] = true }
            session.runIfActive {
                if (!FakeLoc.enable || !FakeLoc.fixedLocationMode) return@runIfActive
                synchronized(managersLock) { ensureWorkerLocked() }
            }
        }.onFailure {
            Logger.error("Unable to schedule fixed GPS reporting")
        }
    }

    /** Call only while holding managersLock. */
    private fun ensureWorkerLocked() {
        if (executor != null) return
        val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PortalFixedGps").apply { isDaemon = true }
        }
        executor = worker
        worker.scheduleWithFixedDelay(::tick, 0, 2, TimeUnit.SECONDS)
    }

    private fun tick() {
        runCatching {
            session.runIfActive {
                if (!FakeLoc.enable || !FakeLoc.fixedLocationMode) {
                    synchronized(managersLock) {
                        executor?.shutdown()
                        executor = null
                    }
                    return@runIfActive
                }
                val snapshot = synchronized(managersLock) { managers.keys.toList() }
                snapshot.forEach { manager -> report(manager) }
            }
        }.onFailure {
            // Never log a source Location, coordinate, or exception payload.
            Logger.error("Fixed GPS reporting failed")
        }
    }

    private fun supportedProvider(manager: Any): String? = runCatching {
        val name = XposedHelpers.callMethod(manager, "getName") as? String
        name?.takeIf { it == LocationManager.GPS_PROVIDER || it == LocationManager.FUSED_PROVIDER }
    }.getOrNull()

    private fun report(manager: Any) {
        runCatching {
            // Startup can arrive on Portal's Binder thread. Reporting into
            // the system manager must run as system_server, just like the
            // periodic worker; the caller identity is always restored.
            val identity = Binder.clearCallingIdentity()
            try {
                val provider = supportedProvider(manager) ?: return
                if (provider == LocationManager.FUSED_PROVIDER && FakeLoc.disableFusedLocation) return
                val managerClass = manager.javaClass
                val resultClass = XposedHelpers.findClassIfExists(
                    "android.location.LocationResult", managerClass.classLoader,
                ) ?: return
                val wrap = XposedHelpers.findMethodExactIfExists(resultClass, "wrap", List::class.java)
                    ?: return
                val report = XposedHelpers.findMethodExactIfExists(managerClass, "onReportLocation", resultClass)
                    ?: return
                val lockField = XposedHelpers.findFieldIfExists(managerClass, "mLock") ?: return
                val stateField = XposedHelpers.findFieldIfExists(managerClass, "mState") ?: return
                val startedField = XposedHelpers.findFieldIfExists(managerClass, "STATE_STARTED") ?: return
                if (!Modifier.isStatic(wrap.modifiers) || wrap.returnType != resultClass ||
                    report.returnType != Void.TYPE
                ) return

                // Construct from configured state only, not from a device fix.
                val fixed = injectLocation(Location(provider))
                val locationResult = wrap.invoke(null, listOf(fixed)) ?: return
                val managerLock = lockField.get(manager) ?: return
                val currentUser = XposedHelpers.callStaticMethod(ActivityManager::class.java, "getCurrentUser") as? Int
                    ?: return
                synchronized(managerLock) {
                    if (!FakeLoc.enable || !FakeLoc.fixedLocationMode ||
                        (provider == LocationManager.FUSED_PROVIDER && FakeLoc.disableFusedLocation) ||
                        stateField.getInt(manager) != startedField.getInt(null) ||
                        XposedHelpers.callMethod(manager, "hasProvider") != true ||
                        XposedHelpers.callMethod(manager, "isEnabled", currentUser) != true
                    ) return
                    // Android processes this result with its normal registration,
                    // cancellation, foreground and AppOps delivery mechanisms.
                    report.invoke(manager, locationResult)
                }
                if (!reportedAttempt) {
                    Logger.info("Experimental fixed GPS report submitted to system manager")
                    reportedAttempt = true
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }.onFailure { error ->
            // Exception classes are useful for diagnosis; messages may contain
            // location data and must not be logged.
            val kind = error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName
            Logger.error("Experimental fixed GPS report failed: $kind")
        }
    }
}
