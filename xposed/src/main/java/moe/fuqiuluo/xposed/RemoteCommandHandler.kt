package moe.fuqiuluo.xposed

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import moe.fuqiuluo.dobby.Dobby
import moe.fuqiuluo.xposed.hooks.LocationServiceHook
import moe.fuqiuluo.xposed.hooks.provider.FixedGpsDispatcher
import moe.fuqiuluo.xposed.hooks.provider.LocationProviderManagerHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.FixedLocationPolicy
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.PortalIpc
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object RemoteCommandHandler {
    private data class ProxyRegistration(val binder: IBinder, val role: String)

    private val proxyBinders = ConcurrentHashMap<String, MutableSet<ProxyRegistration>>()
    private val randomKeys = ConcurrentHashMap<String, String>()
    private val needProxyCmd = arrayOf(
        "start",
        PortalIpc.FIXED_START_COMMAND,
        "stop",
        "set_speed_amp",
        "set_altitude",
        "set_speed",
        "update_location",
        "set_bearing",
        "move",
        "put_config",
    )
    private val stateLock = Any()
    internal val randomKey: String
        get() = keyFor(PortalIpc.LEGACY_PROVIDER)
    private var isLoadedLibrary = false

    /**
     * Commands that would change the mode, turn off a privacy protection, or
     * make the stationary fix report motion while a fixed session is running.
     */
    private val FIXED_SESSION_LOCKED_COMMANDS = setOf(
        PortalIpc.FIXED_START_COMMAND, "start", "start_gnss_mock", "stop_gnss_mock",
        "stop_wifi_mock", "set_speed", "set_bearing", "set_speed_amp", "set_altitude",
    )

    private const val ALLOWED_LIBRARY_PATH = "/data/local/portal-lib/libportal.so"
    private const val XIAOMI_FUSED_ROLE = "XiaomiFusedLocationHook"
    private const val XIAOMI_FUSED_PROCESS = "com.xiaomi.location.fused"

    private fun keyFor(provider: String): String {
        return randomKeys.computeIfAbsent(provider) {
            "${provider}_" + Random.nextDouble()
        }
    }

    private fun proxyBinderSet(provider: String): MutableSet<ProxyRegistration> {
        return proxyBinders.computeIfAbsent(provider) {
            Collections.synchronizedSet(mutableSetOf())
        }
    }

    private fun forwardToProxies(provider: String, commandId: String, rely: Bundle): Boolean {
        if (commandId !in needProxyCmd) return true

        val binders = proxyBinderSet(provider)
        val snapshot = synchronized(binders) { binders.toList() }
        val failed = mutableListOf<ProxyRegistration>()
        snapshot.forEach { registration ->
            val binder = registration.binder
            val accepted = if (!binder.isBinderAlive || !binder.pingBinder()) {
                false
            } else {
                val data = Parcel.obtain()
                try {
                    data.writeBundle(rely)
                    binder.transact(1, data, null, 0)
                } catch (t: Throwable) {
                    Logger.error("Failed to transact with proxyBinder", t)
                    false
                } finally {
                    data.recycle()
                }
            }
            if (!accepted) failed.add(registration)
        }
        if (failed.isNotEmpty()) {
            synchronized(binders) { binders.removeAll(failed.toSet()) }
        }
        if (failed.isNotEmpty()) disableFusedIfProxyMissing(provider)
        return failed.isEmpty()
    }

    private fun hasXiaomiFusedProxy(provider: String): Boolean = hasProxyRole(provider, XIAOMI_FUSED_ROLE)

    private fun hasProxyRole(provider: String, role: String): Boolean {
        val binders = proxyBinderSet(provider)
        val candidates = synchronized(binders) { binders.filter { it.role == role } }
        return candidates.any { it.binder.isBinderAlive && it.binder.pingBinder() }
    }

    private fun disableFusedIfProxyMissing(provider: String) {
        if (provider == PortalIpc.V2_PROVIDER &&
            Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) &&
            !hasXiaomiFusedProxy(provider)
        ) {
            FakeLoc.disableFusedLocation = true
            Logger.warn("Xiaomi fused proxy unavailable; fused location disabled")
        }
    }

    private fun fusedProtectionReady(provider: String): Boolean =
        FixedGpsDispatcher.isVerifiedTestDevice() &&
            LocationProviderManagerHook.isSystemReportHookReady() &&
            LocationServiceHook.isLastLocationHookReady() && hasXiaomiFusedProxy(provider)

    private fun isXiaomiFusedCaller(): Boolean {
        val pid = Binder.getCallingPid()
        if (pid <= 0) return false
        return runCatching {
            File("/proc/$pid/cmdline").readText().substringBefore('\u0000') == XIAOMI_FUSED_PROCESS
        }.getOrDefault(false)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun handleInstruction(command: String, rely: Bundle): Boolean {
        return handleInstruction(PortalIpc.LEGACY_PROVIDER, command, rely)
    }

    /** Handles one authenticated command for exactly one IPC provider. */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun handleInstruction(provider: String, command: String, rely: Bundle): Boolean {
        return handleInstructionInternal(provider, command, rely, trustedSystemForward = false)
    }

    /**
     * Handles a command forwarded over a proxy binder. The binder caller must
     * be a system-scoped process; its provider key was authenticated at the
     * system-side IPC boundary and is not re-created in this process.
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    internal fun handleForwardedInstruction(provider: String, command: String, rely: Bundle): Boolean {
        if (!BinderUtils.isSystemAppsCall()) return false
        return handleInstructionInternal(provider, command, rely, trustedSystemForward = true)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun handleInstructionInternal(
        provider: String,
        command: String,
        rely: Bundle,
        trustedSystemForward: Boolean,
    ): Boolean {
        if (!PortalIpc.isV2Provider(provider)) return false

        val userId = BinderUtils.getCallerUid()
        if (!BinderUtils.isLocationProviderEnabled(userId, provider)) {
            return false
        }

        val providerKey = keyFor(provider)
        // Exchange key -> returns a provider-specific key used to authenticate
        // subsequent commands. A legacy key cannot authorize the v2 provider.
        if (command == "exchange_key") {
            rely.putString("key", providerKey)
            return true
        } else if (!trustedSystemForward && command != providerKey) {
            return false
        }
        val commandId = rely.getString("command_id") ?: return false
        if (commandId == PortalIpc.FIXED_START_COMMAND) {
            rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
            rely.putBoolean(PortalIpc.FIXED_CONFIG_VALID, false)
        }

        if (!trustedSystemForward && provider == PortalIpc.V2_PROVIDER &&
            commandId == "put_config" && rely.containsKey("enable") &&
            rely.getBoolean("enable") != FakeLoc.enable
        ) {
            Logger.warn("Use the fixed start or stop command to change the enabled state")
            return false
        }

        if (!trustedSystemForward && provider == PortalIpc.V2_PROVIDER &&
            commandId == "put_config" && rely.containsKey("disable_fused_location") &&
            !rely.getBoolean("disable_fused_location") && !fusedProtectionReady(provider)
        ) {
            FakeLoc.disableFusedLocation = true
            Logger.warn("Cannot enable fused location while required protections are unavailable")
            return false
        }

        if (!trustedSystemForward && provider == PortalIpc.V2_PROVIDER &&
            FakeLoc.enable && FakeLoc.fixedLocationMode &&
            commandId in FIXED_SESSION_LOCKED_COMMANDS
        ) {
            if (commandId == PortalIpc.FIXED_START_COMMAND) {
                rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
            }
            Logger.warn("Stop the fixed session before changing modes")
            return false
        }

        if (!trustedSystemForward && commandId == PortalIpc.FIXED_START_COMMAND &&
            (!LocationProviderManagerHook.isSystemReportHookReady() ||
                !LocationServiceHook.isLastLocationHookReady())
        ) {
            FakeLoc.disableFusedLocation = true
            rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
            Logger.warn("Required system location report hook is not ready")
            return false
        }

        if (!trustedSystemForward && PortalIpc.rejectsFixedMovement(
                provider, commandId, FakeLoc.enable, FakeLoc.fixedLocationMode,
            )
        ) {
            Logger.warn("Stop the fixed session before changing its location")
            return false
        }

        val needsXiaomiProxy = PortalIpc.requiresXiaomiFusedProxy(
            manufacturer = Build.MANUFACTURER,
            provider = provider,
            command = commandId,
            enabled = FakeLoc.enable,
            fixedMode = FakeLoc.fixedLocationMode,
            forwarded = trustedSystemForward,
        )
        if (needsXiaomiProxy && !hasXiaomiFusedProxy(provider)) {
            FakeLoc.disableFusedLocation = true
            rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
            rely.putString(PortalIpc.FIXED_START_BLOCKED_REASON, PortalIpc.BLOCKED_XIAOMI_FUSED)
            Logger.warn("Xiaomi fused-location proxy is not ready")
            return false
        }

        if (PortalIpc.requiresTelephonyProxy(Build.MANUFACTURER, provider, commandId, trustedSystemForward) &&
            !hasProxyRole(provider, PortalIpc.TELEPHONY_ROLE)
        ) {
            rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
            rely.putString(PortalIpc.FIXED_START_BLOCKED_REASON, PortalIpc.BLOCKED_TELEPHONY)
            Logger.warn("Telephony proxy is not ready; cell information would not be hidden")
            return false
        }

        if (commandId == PortalIpc.FIXED_START_COMMAND &&
            (!rely.containsKey("disable_fused_location") ||
                (!trustedSystemForward && !rely.getBoolean("disable_fused_location") &&
                    !fusedProtectionReady(provider)))
        ) {
            FakeLoc.disableFusedLocation = true
            rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
            Logger.warn("Fixed session fused configuration is unavailable")
            return false
        }

        if (commandId == PortalIpc.FIXED_START_COMMAND && FixedLocationPolicy.validate(
                rely.getDouble("latitude", Double.NaN),
                rely.getDouble("longitude", Double.NaN),
                rely.getDouble("altitude", Double.NaN),
                rely.getFloat("accuracy", Float.NaN),
            ) == null
        ) {
            rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
            return false
        }

        // Stop the local GPS reporter before turning off remote proxies.
        val proxyForwarded = commandId == "stop" || forwardToProxies(provider, commandId, rely)
        if (!proxyForwarded && provider == PortalIpc.V2_PROVIDER &&
            (commandId == PortalIpc.FIXED_START_COMMAND || commandId == "update_location" ||
                (commandId == "put_config" && rely.containsKey("disable_fused_location") &&
                    !rely.getBoolean("disable_fused_location")))
        ) {
            if (commandId == PortalIpc.FIXED_START_COMMAND) {
                forwardToProxies(provider, "stop", Bundle().apply { putString("command_id", "stop") })
                rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
            }
            if (commandId == PortalIpc.FIXED_START_COMMAND || commandId == "put_config") {
                FakeLoc.disableFusedLocation = true
            }
            Logger.warn("Fixed-location command was not accepted by every registered proxy")
            return false
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("commandId=$commandId, provider=$provider")
        }

        when (commandId) {
            "set_proxy" -> {
                val binder = rely.getBinder("proxy") ?: return false
                val role = rely.getString("proxy_role") ?: "generic"
                if (role == XIAOMI_FUSED_ROLE && !isXiaomiFusedCaller()) return false
                val registration = ProxyRegistration(binder, role)
                val binders = proxyBinderSet(provider)
                if (role == XIAOMI_FUSED_ROLE && provider == PortalIpc.V2_PROVIDER) {
                    val deathRecipient = IBinder.DeathRecipient {
                        binders.remove(registration)
                        disableFusedIfProxyMissing(provider)
                    }
                    binders.add(registration)
                    if (runCatching { binder.linkToDeath(deathRecipient, 0) }.isFailure) {
                        binders.remove(registration)
                        disableFusedIfProxyMissing(provider)
                        Logger.warn("Unable to observe Xiaomi fused proxy lifetime")
                        return false
                    }
                    if (!binder.isBinderAlive) {
                        binders.remove(registration)
                        disableFusedIfProxyMissing(provider)
                        return false
                    }
                } else {
                    binders.add(registration)
                }
                Logger.info("SubProxyBinder registered for provider=$provider role=$role")
                return true
            }
            "start" -> {
                val speed = rely.getDouble("speed", FakeLoc.speed)
                val altitude = rely.getDouble("altitude", FakeLoc.altitude)
                val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)

                synchronized(stateLock) {
                    FakeLoc.fixedLocationMode = false
                    FakeLoc.enable = true
                    FakeLoc.enableMockGnss = true  // 同时启动 GNSS Mock
                    FakeLoc.enableMockWifi = true  // 同时启动 WiFi Mock，防止通过WiFi泄露真实位置
                    FakeLoc.speed = speed
                    FakeLoc.altitude = altitude
                    FakeLoc.accuracy = accuracy
                    FakeLoc.lastLocation = null
                }

                if (isLoadedLibrary) {
                    Dobby.setStatus(true)
                }
                
                Logger.info("Location Mock 启动，同时启动 GNSS Mock 和 WiFi Mock")

                return true
            }
            PortalIpc.FIXED_START_COMMAND -> {
                val fixedLocation = FixedLocationPolicy.validate(
                    latitude = rely.getDouble("latitude", Double.NaN),
                    longitude = rely.getDouble("longitude", Double.NaN),
                    altitude = rely.getDouble("altitude", Double.NaN),
                    accuracy = rely.getFloat("accuracy", Float.NaN),
                ) ?: run {
                    rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
                    rely.putBoolean(PortalIpc.FIXED_CONFIG_VALID, false)
                    Logger.warn("Rejected invalid fixed-location start request")
                    return false
                }
                val fusedDisabled = rely.getBoolean("disable_fused_location", true)

                val commitFixed: () -> Unit = {
                    synchronized(stateLock) {
                        // Commit the coordinate pair before enabling the session.
                        FakeLoc.setCoordinates(fixedLocation.latitude, fixedLocation.longitude)
                        FakeLoc.altitude = fixedLocation.altitude
                        FakeLoc.accuracy = fixedLocation.accuracy
                        FakeLoc.speed = 0.0
                        FakeLoc.speedAmplitude = 0.0
                        FakeLoc.bearing = 0.0
                        FakeLoc.hasBearings = false
                        FakeLoc.fixedLocationMode = true
                        FakeLoc.disableGetCurrentLocation = false
                        FakeLoc.disableFusedLocation = fusedDisabled
                        FakeLoc.lastLocation = null
                        FakeLoc.enable = true
                        FakeLoc.enableMockGnss = true
                        FakeLoc.enableMockWifi = true
                    }
                }

                if (FakeLoc.isSystemServerProcess &&
                    !runCatching { FixedGpsDispatcher.begin(commitFixed) }.getOrDefault(false)
                ) {
                    forwardToProxies(provider, "stop", Bundle().apply { putString("command_id", "stop") })
                    rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
                    Logger.warn("Previous fixed GPS session is still stopping")
                    return false
                } else if (!FakeLoc.isSystemServerProcess) {
                    commitFixed()
                }

                if (!trustedSystemForward && !fusedDisabled) {
                    disableFusedIfProxyMissing(provider)
                    if (FakeLoc.disableFusedLocation) {
                        val clearLocalState: () -> Unit = {
                            synchronized(stateLock) {
                                FakeLoc.enable = false
                                FakeLoc.fixedLocationMode = false
                                FakeLoc.enableMockGnss = false
                                FakeLoc.enableMockWifi = false
                                FakeLoc.lastLocation = null
                                FakeLoc.disableFusedLocation = true
                            }
                        }
                        if (!FixedGpsDispatcher.stop(clearLocalState)) clearLocalState()
                        forwardToProxies(provider, "stop", Bundle().apply { putString("command_id", "stop") })
                        rely.putBoolean(PortalIpc.FIXED_START_ACK, false)
                        return false
                    }
                }

                if (FakeLoc.isSystemServerProcess) {
                    kotlin.runCatching {
                        // The state is committed and enabled before notifying
                        // listeners, so callbacks cannot retain the previous
                        // location simply because the provider is idle.
                        LocationServiceHook.callOnLocationChanged()
                    }.onFailure {
                        Logger.error("Failed to push fixed location callback", it)
                    }
                }

                if (isLoadedLibrary) {
                    Dobby.setStatus(true)
                }

                rely.putBoolean(PortalIpc.FIXED_START_ACK, true)
                rely.putBoolean(PortalIpc.FIXED_CONFIG_VALID, true)
                rely.putBoolean("is_start", true)
                rely.putDouble("latitude", fixedLocation.latitude)
                rely.putDouble("longitude", fixedLocation.longitude)
                rely.putDouble("altitude", fixedLocation.altitude)
                rely.putFloat("accuracy", fixedLocation.accuracy)
                rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                return true
            }
            "stop" -> {
                val localStopped = FixedGpsDispatcher.stop {
                    synchronized(stateLock) {
                        FakeLoc.enable = false
                        FakeLoc.enableMockGnss = false  // 同时关闭 GNSS Mock
                        FakeLoc.enableMockWifi = false  // 同时关闭 WiFi Mock
                        FakeLoc.hasBearings = false
                        FakeLoc.fixedLocationMode = false
                        FakeLoc.lastLocation = null
                    }
                }
                if (!localStopped) return false
                val stopForwarded = forwardToProxies(provider, commandId, rely)
                if (isLoadedLibrary) {
                    Dobby.setStatus(false)
                }
                Logger.info("Location Mock 关闭，同时关闭 GNSS Mock 和 WiFi Mock")
                return stopForwarded
            }
            "is_start" -> {
                rely.putBoolean("is_start", FakeLoc.enable)
                return true
            }
            "start_gnss_mock" -> {
                FakeLoc.fixedLocationMode = false
                FakeLoc.enable = true  // GNSS Mock 依赖 Location Mock
                FakeLoc.enableMockGnss = true
                Logger.info("GNSS Mock 启动，同时启动 Location Mock")
                return true
            }
            "stop_gnss_mock" -> {
                FakeLoc.enable = false  // GNSS Mock 关闭时同时关闭 Location Mock
                FakeLoc.enableMockGnss = false
                FakeLoc.fixedLocationMode = false
                FakeLoc.lastLocation = null
                Logger.info("GNSS Mock 关闭，同时关闭 Location Mock")
                return true
            }
            "is_gnss_start" -> {
                rely.putBoolean("is_gnss_start", FakeLoc.enableMockGnss)
                return true
            }
            "is_wifi_mock_start" -> {
                rely.putBoolean("is_wifi_mock_start", FakeLoc.enableMockWifi)
                return true
            }
            "start_wifi_mock" -> {
                FakeLoc.enableMockWifi = true
                return true
            }
            "stop_wifi_mock" -> {
                FakeLoc.enableMockWifi = false
                return true
            }
            "get_location" -> {
                val coordinates = FakeLoc.coordinateSnapshot()
                rely.putDouble("lat", coordinates.latitude)
                rely.putDouble("lon", coordinates.longitude)
                return true
            }
            "get_fixed_config" -> {
                val coordinates = FakeLoc.coordinateSnapshot()
                val config = FixedLocationPolicy.validate(
                    latitude = coordinates.latitude,
                    longitude = coordinates.longitude,
                    altitude = FakeLoc.altitude,
                    accuracy = FakeLoc.accuracy,
                )
                if (config == null) {
                    rely.putBoolean(PortalIpc.FIXED_CONFIG_VALID, false)
                    return true
                }
                rely.putBoolean(PortalIpc.FIXED_CONFIG_VALID, true)
                rely.putDouble("latitude", config.latitude)
                rely.putDouble("longitude", config.longitude)
                rely.putDouble("altitude", config.altitude)
                rely.putFloat("accuracy", config.accuracy)
                rely.putBoolean("fixed_location_mode", FakeLoc.fixedLocationMode)
                rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                return true
            }
            "get_listener_size" -> {
                rely.putInt("size", LocationServiceHook.locationListeners.size)
                return true
            }
            "get_speed" -> {
                rely.putDouble("speed", FakeLoc.speed)
                return true
            }
            "get_bearing" -> {
                rely.putDouble("bearing", FakeLoc.bearing)
                return true
            }
            "get_altitude" -> {
                rely.putDouble("altitude", FakeLoc.altitude)
                return true
            }
            "set_speed_amp" -> {
                val speedAmplitude = rely.getDouble("speed_amplitude", 1.0)
                FakeLoc.speedAmplitude = speedAmplitude
                return true
            }
            "set_altitude" -> {
                val altitude = rely.getDouble("altitude", 0.0)
                FakeLoc.altitude = altitude
                return true
            }
            "set_speed" -> {
                val speed = rely.getDouble("speed", 0.0)
                FakeLoc.speed = speed
                return true
            }
            "set_bearing" -> {
                val bearing = rely.getDouble("bearing", 0.0)
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
                return true
            }
            "move" -> {
                val distance = rely.getDouble("n", 0.0)
                if (distance == 0.0) return true
                val bearing = rely.getDouble("bearing", 0.0)
                val newLoc = FakeLoc.moveLocation(
                    n = distance,
                    angle = bearing
                )
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("move command applied")
                }
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
                return updateCoordinate(newLoc.first, newLoc.second).also {
                    if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                }
            }
            "update_location" -> {
                val mode = rely.getString("mode")
                var newLat = rely.getDouble("lat", 0.0)
                var newLon = rely.getDouble("lon", 0.0)
                when(mode) {
                    "+" -> {
                        newLat += FakeLoc.latitude
                        newLon += FakeLoc.longitude
                        return updateCoordinate(newLat, newLon).also {
                            if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                        }
                    }
                    "-" -> {
                        newLat = FakeLoc.latitude - newLat
                        newLon = FakeLoc.longitude - newLon
                        return updateCoordinate(newLat, newLon).also {
                            if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                        }
                    }
                    "*" -> {
                        newLat *= FakeLoc.latitude
                        newLon *= FakeLoc.longitude
                        return updateCoordinate(newLat, newLon).also {
                            if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                        }
                    }
                    "/" -> {
                        // 检查除数是否为零
                        if (newLat == 0.0 || newLon == 0.0) {
                            return false
                        }
                        // 当前值 / 新值（与 "-" 操作保持一致）
                        newLat = FakeLoc.latitude / newLat
                        newLon = FakeLoc.longitude / newLon
                        return updateCoordinate(newLat, newLon).also {
                            if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                        }
                    }
                    "=" -> {
                        return updateCoordinate(newLat, newLon).also {
                            if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                        }
                    }
                    "random" -> {
                        return updateCoordinate(Random.nextDouble(-90.0, 90.0), Random.nextDouble(-180.0, 180.0)).also {
                            if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                        }
                    }
                }
                return true
            }
            "put_config" -> {
                val enable = rely.getBoolean("enable", FakeLoc.enable)
                val speed = rely.getDouble("speed", FakeLoc.speed)
                val altitude = rely.getDouble("altitude", FakeLoc.altitude)
                val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)
                val enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                val disableGetCurrentLocation = rely.getBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
                val disableRegisterLocationListener = rely.getBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
                val disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                val needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                var minSatellites = rely.getInt("min_satellites", 12)
                if (minSatellites < 0) {
                    minSatellites = 12
                }

                val enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
                val enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
                val disableRequestGeofence = rely.getBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
                val disableGetFromLocation = rely.getBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)

                FakeLoc.enable = enable
                // A fixed point is stationary; ignore the saved movement speed.
                if (!(FakeLoc.enable && FakeLoc.fixedLocationMode)) FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy
                FakeLoc.enableDebugLog = enableDebugLog
                FakeLoc.disableGetCurrentLocation = disableGetCurrentLocation
                FakeLoc.disableRegisterLocationListener = disableRegisterLocationListener
                FakeLoc.disableFusedLocation = disableFusedLocation
                if (!trustedSystemForward && FakeLoc.enable && FakeLoc.fixedLocationMode) {
                    disableFusedIfProxyMissing(provider)
                    if (!disableFusedLocation && FakeLoc.disableFusedLocation) return false
                }
                FakeLoc.needDowngradeToCdma = needDowngradeToCdma
                FakeLoc.minSatellites = minSatellites
                FakeLoc.enableAGPS = enableAGPS
                FakeLoc.enableNMEA = enableNMEA
                FakeLoc.disableRequestGeofence = disableRequestGeofence
                FakeLoc.disableGetFromLocation = disableGetFromLocation
                if (FakeLoc.enable && FakeLoc.fixedLocationMode) {
                    FakeLoc.disableGetCurrentLocation = false
                }
                return true
            }
            "sync_config" -> {
                rely.putBoolean("enable", FakeLoc.enable)
                val coordinates = FakeLoc.coordinateSnapshot()
                rely.putDouble("latitude", coordinates.latitude)
                rely.putDouble("longitude", coordinates.longitude)
                rely.putDouble("altitude", FakeLoc.altitude)
                rely.putFloat("accuracy", FakeLoc.accuracy)
                rely.putDouble("speed", FakeLoc.speed)
                rely.putDouble("speed_amplitude", FakeLoc.speedAmplitude)
                rely.putBoolean("has_bearings", FakeLoc.hasBearings)
                rely.putDouble("bearing", FakeLoc.bearing)
                rely.putBoolean("fixed_location_mode", FakeLoc.fixedLocationMode)
                rely.putBoolean("enable_log", FakeLoc.enableLog)
                rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                rely.putBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
                rely.putBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
                rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
                rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
                rely.putBoolean("hide_mock", FakeLoc.hideMock)
                rely.putBoolean("hook_wifi", FakeLoc.hookWifi)
                rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                return true
            }
            "broadcast_location" -> {
                LocationServiceHook.callOnLocationChanged()
                return true
            }
            "load_library" -> {
                val path = rely.getString("path") ?: return false
                if (!isAllowedLibraryPath(path)) {
                    rely.putString("result", "rejected")
                    Logger.warn("Rejected dynamic library path")
                    return false
                }

                if (isLoadedLibrary && path == ALLOWED_LIBRARY_PATH) {
                    rely.putString("result", "success")
                    return true
                }
                val isManagedFile = runCatching {
                    val file = File(path)
                    file.isFile && file.canonicalPath == ALLOWED_LIBRARY_PATH
                }.getOrDefault(false)
                if (!isManagedFile) {
                    rely.putString("result", "rejected")
                    Logger.warn("Rejected unmanaged dynamic library file")
                    return false
                }
                runCatching {
                    System.load(path)
                }.onSuccess {
                    rely.putString("result", "success")
                    isLoadedLibrary = true
                }.onFailure {
                    rely.putString("result", it.stackTraceToString())
                }

                if (isLoadedLibrary) {
                    Dobby.setStatus(FakeLoc.enable)
                }

                return isLoadedLibrary && rely.getString("result") == "success"
            }
            else -> return false
        }
    }

//    private var hasHookSensor = false
//
//    private fun tryHookSensor(classLoader: ClassLoader = FakeLoc::class.java.classLoader!!) {
//        if (hasHookSensor || proxyBinders.isNullOrEmpty()) return
//
//
//
//        hasHookSensor = true
//    }

//    private fun generateLocation(): Location {
//        val (location, realLocation) = if (FakeLocationConfig.lastLocation != null) {
//            (FakeLocationConfig.lastLocation!! to true)
//        } else {
//            (Location(LocationManager.GPS_PROVIDER) to false)
//        }
//
//        return LocationServiceProxyHook.injectLocation(location, realLocation)
//    }

    internal fun isAllowedLibraryPath(path: String): Boolean {
        return path == ALLOWED_LIBRARY_PATH
    }

    private fun updateCoordinate(newLat: Double, newLon: Double): Boolean {
        if (FixedLocationPolicy.validate(newLat, newLon, FakeLoc.altitude, FakeLoc.accuracy) != null) {
            FakeLoc.setCoordinates(newLat, newLon)
            return true
        } else {
            Logger.error("Invalid latitude or longitude")
            return false
        }
    }
}
