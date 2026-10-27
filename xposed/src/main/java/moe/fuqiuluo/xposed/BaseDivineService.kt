package moe.fuqiuluo.xposed

import android.content.Context
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.FixedLocationPolicy
import moe.fuqiuluo.xposed.utils.PortalIpc
import moe.fuqiuluo.xposed.utils.Logger

abstract class BaseDivineService {
    /**
     * if the hook is TelephonyService? or other service?
     * this service may not be in the same space as the `system_server`,
     * so a binder is used to talk.
     */
    protected fun initDivineService(from: String, retryCount: Int = 0): Boolean {
        if (FakeLoc.isSystemServerProcess) {
            return true
        }
        if (!isAllowedSyncCaller()) {
            Logger.warn("Skip DivineService sync from non-system scoped process: $from")
            return false
        }

        fun tryFetchLocationManager(): LocationManager? {
            var locationManager = BinderUtils.getSystemContext()?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            var count = 0
            while (locationManager == null) {
                Thread.sleep(1000)
                if (count++ > 10) {
                    break
                }
                locationManager = BinderUtils.getSystemContext()?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            }
            return locationManager
        }

        val locationManager = tryFetchLocationManager()
        if (locationManager == null) {
            Logger.error("LocationManager not found in $from")
            return false
        }

        val provider = resolveProvider(locationManager)
        if (provider == null) {
            if (retryCount > 10) {
                return false
            }
            Thread.sleep(100)
            return initDivineService(from, retryCount + 1)
        }

        var randomKey = ""
        val rely = Bundle()
        if(locationManager.sendExtraCommand(provider, "exchange_key", rely)) {
            rely.getString("key")?.let {
                randomKey = it
            }
        }

        if (randomKey.isEmpty()){
            Logger.error("Failed to init service in $from")
            return false
        }

        if (!syncConfig(locationManager, provider, randomKey)) {
            return false
        }

        rely.putBinder("proxy", object: Binder() {
            override fun getInterfaceDescriptor(): String {
                return "moe.fuqiuluo.portal.service.${from}Helper"
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    if (!BinderUtils.isSystemAppsCall()) return false
                    val bundle = kotlin.runCatching {
                        data.readBundle(javaClass.classLoader)
                    }.getOrNull() ?: return false
                    val handled = RemoteCommandHandler.handleForwardedInstruction(provider, randomKey, bundle)
                    if (!handled) {
                        Logger.error("Failed to handle instruction in $from")
                    }
                    return handled
                }
                return super.onTransact(code, data, reply, flags)
            }
        })
        rely.putString("command_id", "set_proxy")
        rely.putString("proxy_role", from)
        if (!locationManager.sendExtraCommand(provider, randomKey, rely)) {
            Logger.error("Failed to init service proxy in $from")
            return false
        }
        return true
    }

    /**
     * Synchronize configurations in different processes
     */
    private fun syncConfig(locationManager: LocationManager, provider: String, randomKey: String): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "sync_config")
        if(locationManager.sendExtraCommand(provider, randomKey, rely)) {
            val enabled = rely.getBoolean("enable", FakeLoc.enable)
            val latitude = rely.getDouble("latitude", FakeLoc.latitude)
            val longitude = rely.getDouble("longitude", FakeLoc.longitude)
            val altitude = rely.getDouble("altitude", FakeLoc.altitude)
            val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)
            if (FixedLocationPolicy.validate(
                    latitude,
                    longitude,
                    altitude,
                    accuracy,
                ) == null
            ) {
                Logger.error("Invalid DivineService location configuration")
                return false
            }
            FakeLoc.setCoordinates(latitude, longitude)
            if (altitude.isFinite()) {
                FakeLoc.altitude = altitude
            }
            if (accuracy.isFinite() && accuracy >= 0.0f) {
                FakeLoc.accuracy = accuracy
            }
            FakeLoc.speed = rely.getDouble("speed", FakeLoc.speed)
            FakeLoc.speedAmplitude = rely.getDouble("speed_amplitude", FakeLoc.speedAmplitude)
            FakeLoc.hasBearings = rely.getBoolean("has_bearings", FakeLoc.hasBearings)
            FakeLoc.bearing = rely.getDouble("bearing", FakeLoc.bearing)
            FakeLoc.fixedLocationMode = rely.getBoolean("fixed_location_mode", FakeLoc.fixedLocationMode)
            // Do not import or retain a cached real Location from another
            // process. The callback daemon can create a fresh fixed value.
            FakeLoc.lastLocation = null
            FakeLoc.enableLog = rely.getBoolean("enable_log", FakeLoc.enableLog)
            FakeLoc.enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
            FakeLoc.disableGetCurrentLocation = rely.getBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
            if (enabled && FakeLoc.fixedLocationMode) {
                FakeLoc.disableGetCurrentLocation = false
            }
            FakeLoc.disableRegisterLocationListener = rely.getBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
            FakeLoc.disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
            FakeLoc.enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
            FakeLoc.enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
            FakeLoc.hideMock = rely.getBoolean("hide_mock", FakeLoc.hideMock)
            FakeLoc.hookWifi = rely.getBoolean("hook_wifi", FakeLoc.hookWifi)
            FakeLoc.needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
            // Publish the active state only after the complete configuration.
            FakeLoc.enable = enabled
            Logger.debug("Synced config for DivineService")
            return true
        } else {
            Logger.error("Failed to sync config for DivineService")
            return false
        }
    }

    private fun resolveProvider(locationManager: LocationManager): String? {
        // This build must never fall back to the old module's control channel.
        return PortalIpc.V2_PROVIDER.takeIf {
            kotlin.runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false)
        }
    }

    private fun isAllowedSyncCaller(): Boolean {
        // This method runs during process initialization, outside an inbound
        // Binder transaction. Use the process UID explicitly; getCallerUid()
        // must remain -1 in this context and must never be substituted.
        if (BinderUtils.isSystemIdentityProcess()) return true
        val processUid = BinderUtils.getProcessUid()
        val packageNames = BinderUtils.getUidPackageNames(uid = processUid)?.toSet() ?: return false
        return packageNames.size == 1 && packageNames.single() in setOf(
            PortalIpc.LEGACY_APPLICATION_ID,
            PortalIpc.V2_APPLICATION_ID,
        )
    }
}
