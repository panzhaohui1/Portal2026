package moe.fuqiuluo.portal.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import moe.fuqiuluo.portal.Portal
import moe.fuqiuluo.portal.android.root.ShellUtils
import moe.fuqiuluo.portal.ext.altitude
import moe.fuqiuluo.portal.ext.debug
import moe.fuqiuluo.portal.ext.disableFusedProvider
import moe.fuqiuluo.portal.ext.disableGetCurrentLocation
import moe.fuqiuluo.portal.ext.disableRegisterLocationListener
import moe.fuqiuluo.portal.ext.enableAGPS
import moe.fuqiuluo.portal.ext.enableGetFromLocation
import moe.fuqiuluo.portal.ext.enableNMEA
import moe.fuqiuluo.portal.ext.enableRequestGeofence
import moe.fuqiuluo.portal.ext.minSatelliteCount
import moe.fuqiuluo.portal.ext.needDowngradeToCdma
import moe.fuqiuluo.portal.ext.speed
import moe.fuqiuluo.portal.ext.reportDuration
import moe.fuqiuluo.portal.ext.loopBroadcastlocation
import moe.fuqiuluo.xposed.utils.FixedLocationConfig
import moe.fuqiuluo.xposed.utils.FixedLocationPolicy
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.PortalIpc
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object MockServiceHelper {
    private data class FixedLocationReadback(
        val config: FixedLocationConfig,
        val fixedMode: Boolean,
        val fusedDisabled: Boolean,
    )

    const val LEGACY_PROVIDER_NAME = PortalIpc.LEGACY_PROVIDER
    const val V2_PROVIDER_NAME = PortalIpc.V2_PROVIDER

    /** Preserves the old public property while selecting the v2 namespace at runtime. */
    val PROVIDER_NAME: String
        get() = providerName()

    private val randomKeys = ConcurrentHashMap<String, String>()

    private var loopThread :Thread ?= null
    @Volatile private var isRunning = false

    private fun providerName(context: Context? = null): String {
        val applicationId = context?.packageName
            ?: runCatching { Portal.appContext.packageName }.getOrNull()
        return PortalIpc.providerForApplicationId(applicationId)
    }

    private fun randomKey(provider: String = providerName()): String? {
        return randomKeys[provider]
    }

    private fun sendCommand(
        locationManager: LocationManager,
        provider: String,
        command: String,
        rely: Bundle,
    ): Boolean {
        return runCatching {
            locationManager.sendExtraCommand(provider, command, rely)
        }.onFailure {
            Log.e("MockServiceHelper", "Location command failed: $command", it)
        }.getOrDefault(false)
    }

    private fun sendAuthenticated(
        locationManager: LocationManager,
        rely: Bundle,
        provider: String = providerName(),
    ): Boolean {
        val key = randomKey(provider) ?: return false
        return sendCommand(locationManager, provider, key, rely)
    }

    fun tryInitService(locationManager: LocationManager) {
        val provider = providerName()
        val rely = Bundle()
        Log.d("MockServiceHelper", "Try to init service")
        if(sendCommand(locationManager, provider, "exchange_key", rely)) {
            rely.getString("key")?.let {
                randomKeys[provider] = it
                Log.d("MockServiceHelper", "Service init success")
            } ?: run {
                randomKeys.remove(provider)
                Log.e("MockServiceHelper", "Service init returned no key")
            }
        } else {
            randomKeys.remove(provider)
            Log.e("MockServiceHelper", "Failed to init service")
        }
    }

    /** Returns null when the service did not return a readable status. */
    fun getMockStatus(locationManager: LocationManager): Boolean? {
        val rely = Bundle()
        rely.putString("command_id", "is_start")
        if (sendAuthenticated(locationManager, rely) && rely.containsKey("is_start")) {
            return rely.getBoolean("is_start")
        }
        return null
    }

    fun isMockStart(locationManager: LocationManager): Boolean = getMockStatus(locationManager) == true

    fun isGnssMockStart(locationManager: LocationManager): Boolean {
        // 因为 Location Mock 和 GNSS Mock 现在完全联动
        // 所以检查 is_start 即可（与 isMockStart 相同）
        return isMockStart(locationManager)
    }

    fun startGnssMock(locationManager: LocationManager, context: android.content.Context): Boolean {
        val provider = providerName(context)
        if (randomKey(provider) == null) return false
        // 使用 start 命令以确保完全联动
        val rely = Bundle()
        rely.putString("command_id", "start")
        // 从 SharedPreferences 读取配置，而不是从 Xposed 模块的 FakeLoc
        val sharedPrefs = context.getSharedPreferences(provider, android.content.Context.MODE_PRIVATE)
        rely.putDouble("speed", sharedPrefs.getFloat("speed", 1.2f).toDouble())
        rely.putDouble("altitude", sharedPrefs.getFloat("altitude", 0f).toDouble())
        rely.putFloat("accuracy", sharedPrefs.getFloat("accuracy", 0f))
        if (!sendAuthenticated(locationManager, rely, provider)) return false
        startLoopBroadcastLocation(locationManager)
        return true
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        // 使用 stop 命令以确保完全联动
        val rely = Bundle()
        rely.putString("command_id", "stop")
        stopLoopBroadcastLocation()
        return sendAuthenticated(locationManager, rely)
    }

    fun isWifiMockStart(locationManager: LocationManager): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "is_wifi_mock_start")
        if(sendAuthenticated(locationManager, rely)) {
            return rely.getBoolean("is_wifi_mock_start")
        }
        return false
    }

    fun startWifiMock(locationManager: LocationManager): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "start_wifi_mock")
        return sendAuthenticated(locationManager, rely)
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "stop_wifi_mock")
        return sendAuthenticated(locationManager, rely)
    }

    fun tryOpenMock(
        locationManager: LocationManager,
        speed: Double,
        altitude: Double,
        accuracy: Float,
    ): Boolean {
        if (randomKey() == null) return false
        val rely = Bundle()
        rely.putString("command_id", "start")
        rely.putDouble("speed", speed)
        rely.putDouble("altitude", altitude)
        rely.putFloat("accuracy", accuracy)
        // Xposed 层已实现 Location Mock 和 GNSS Mock 的联动
        return if(sendAuthenticated(locationManager, rely)) {
            val started = isMockStart(locationManager)
            if (started) {
                startLoopBroadcastLocation(locationManager)
            }
            started
        } else {
            false
        }
    }

    fun tryCloseMock(locationManager: LocationManager): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "stop")
        stopLoopBroadcastLocation()
        
        // Xposed 层已实现 Location Mock 和 GNSS Mock 的联动
        return sendAuthenticated(locationManager, rely) && getMockStatus(locationManager) == false
    }

    /**
     * Starts one fixed-location session. The system-side command validates and
     * commits the complete snapshot before enabling the hook; this method then
     * reads back every configured field before reporting success.
     */
    /** Protection named by the system side when it refused the last fixed start, if any. */
    @Volatile
    var lastFixedStartBlockedReason: String? = null
        private set

    fun startFixedMock(
        locationManager: LocationManager,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        fusedDisabled: Boolean,
    ): Boolean {
        lastFixedStartBlockedReason = null
        val expected = FixedLocationPolicy.validate(latitude, longitude, altitude, accuracy)
            ?: return false
        val rely = Bundle().apply {
            putString("command_id", PortalIpc.FIXED_START_COMMAND)
            putDouble("latitude", expected.latitude)
            putDouble("longitude", expected.longitude)
            putDouble("altitude", expected.altitude)
            putFloat("accuracy", expected.accuracy)
            putBoolean("disable_fused_location", fusedDisabled)
        }

        val commandAccepted = sendAuthenticated(locationManager, rely)
        lastFixedStartBlockedReason = rely.getString(PortalIpc.FIXED_START_BLOCKED_REASON)
        if (!commandAccepted) {
            rollbackFailedFixedStart(locationManager)
            return false
        }
        if (!rely.getBoolean(PortalIpc.FIXED_START_ACK, false)) {
            // A transport-level success without the explicit acknowledgement
            // may still mean that an older or partially compatible hook ran.
            rollbackFailedFixedStart(locationManager)
            return false
        }

        val enabled = isMockStart(locationManager)
        val location = getLocation(locationManager)
        val actual = getFixedConfig(locationManager)
        val confirmed = FixedLocationPolicy.isConfirmed(
            expected = expected,
            commandAccepted = commandAccepted,
            acknowledgement = true,
            enabled = enabled,
            fixedMode = actual?.fixedMode == true,
            actual = actual?.config,
            readBackCoordinates = location,
        )
        if (confirmed && actual?.fusedDisabled == fusedDisabled &&
            rely.containsKey("disable_fused_location") &&
            rely.getBoolean("disable_fused_location") == fusedDisabled
        ) return true

        rollbackFailedFixedStart(locationManager)
        return false
    }

    private fun getFixedConfig(locationManager: LocationManager): FixedLocationReadback? {
        val rely = Bundle().apply { putString("command_id", "get_fixed_config") }
        if (!sendAuthenticated(locationManager, rely) ||
            !rely.getBoolean(PortalIpc.FIXED_CONFIG_VALID, false) ||
            !rely.containsKey("disable_fused_location")
        ) {
            return null
        }
        val config = FixedLocationPolicy.validate(
            latitude = rely.getDouble("latitude", Double.NaN),
            longitude = rely.getDouble("longitude", Double.NaN),
            altitude = rely.getDouble("altitude", Double.NaN),
            accuracy = rely.getFloat("accuracy", Float.NaN),
        ) ?: return null
        return FixedLocationReadback(
            config = config,
            fixedMode = rely.getBoolean("fixed_location_mode", false),
            fusedDisabled = rely.getBoolean("disable_fused_location"),
        )
    }

    fun isFixedFusedDisabled(locationManager: LocationManager): Boolean? {
        val readback = getFixedConfig(locationManager) ?: return null
        return if (readback.fixedMode) readback.fusedDisabled else null
    }

    private fun rollbackFailedFixedStart(locationManager: LocationManager) {
        val rely = Bundle().apply { putString("command_id", "stop") }
        sendAuthenticated(locationManager, rely)
    }

    fun getLocation(locationManager: LocationManager): Pair<Double, Double>? {
        val rely = Bundle()
        rely.putString("command_id", "get_location")
        if (sendAuthenticated(locationManager, rely) && rely.containsKey("lat") && rely.containsKey("lon")) {
            return Pair(rely.getDouble("lat"), rely.getDouble("lon"))
        }
        return null
    }

    fun getLocationListenerSize(locationManager: LocationManager): Int? {
        val rely = Bundle()
        rely.putString("command_id", "get_listener_size")
        if(sendAuthenticated(locationManager, rely)) {
            return rely.getInt("size")
        }
        return null
    }

    fun broadcastLocation(locationManager: LocationManager): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "broadcast_location")
        return sendAuthenticated(locationManager, rely)
    }

    fun setBearing(locationManager: LocationManager, bearing: Double): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "set_bearing")
        rely.putDouble("bearing", bearing)
        return sendAuthenticated(locationManager, rely)
    }

    fun setSpeed(locationManager: LocationManager, speed: Float): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "set_speed")
        rely.putFloat("speed", speed)
        return sendAuthenticated(locationManager, rely)
    }

    fun setAltitude(locationManager: LocationManager, altitude: Double): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "set_altitude")
        rely.putDouble("altitude", altitude)
        return sendAuthenticated(locationManager, rely)
    }

    fun setSpeedAmplitude(locationManager: LocationManager, speedAmplitude: Double): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "set_speed_amp")
        rely.putDouble("speed_amplitude", speedAmplitude)
        return sendAuthenticated(locationManager, rely)
    }

    fun getSpeed(locationManager: LocationManager): Float? {
        val rely = Bundle()
        rely.putString("command_id", "get_speed")
        if(sendAuthenticated(locationManager, rely)) {
            return rely.getFloat("speed")
        }
        return null
    }

    fun getBearing(locationManager: LocationManager): Float? {
        val rely = Bundle()
        rely.putString("command_id", "get_bearing")
        if(sendAuthenticated(locationManager, rely)) {
            return rely.getFloat("bearing")
        }
        return null
    }

    fun getAltitude(locationManager: LocationManager): Double? {
        val rely = Bundle()
        rely.putString("command_id", "get_altitude")
        if(sendAuthenticated(locationManager, rely)) {
            return rely.getDouble("altitude")
        }
        return null
    }

    fun move(locationManager: LocationManager, distance: Double, bearing: Double): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "move")
        rely.putDouble("n", distance)
        rely.putDouble("bearing", bearing)

        if (FakeLoc.enableDebugLog) {
            Log.d("MockServiceHelper", "move command sent")
        }

        return sendAuthenticated(locationManager, rely)
    }

    fun setLocation(locationManager: LocationManager, lat: Double, lon: Double): Boolean {
        return updateLocation(locationManager, lat, lon, "=")
    }

    /** Confirm a fixed-location change against the system-side state. */
    fun updateFixedLocation(locationManager: LocationManager, lat: Double, lon: Double): Boolean {
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) {
            return false
        }
        if (!setLocation(locationManager, lat, lon)) return false
        val actual = getFixedConfig(locationManager) ?: return false
        return getMockStatus(locationManager) == true && actual.fixedMode &&
            actual.config.latitude == lat && actual.config.longitude == lon &&
            getLocation(locationManager) == (lat to lon)
    }

    fun updateLocation(locationManager: LocationManager, lat: Double, lon: Double, mode: String): Boolean {
        val rely = Bundle()
        rely.putString("command_id", "update_location")
        rely.putDouble("lat", lat)
        rely.putDouble("lon", lon)
        rely.putString("mode", mode)
        return sendAuthenticated(locationManager, rely)
    }

    fun loadLibrary(locationManager: LocationManager, path: String): String? {
        val rely = Bundle()
        rely.putString("command_id", "load_library")
        rely.putString("path", path)
        if(sendAuthenticated(locationManager, rely)) {
            return rely.getString("result")
        }
        return null
    }

    fun putConfig(locationManager: LocationManager, context: Context): Boolean {
        FakeLoc.altitude = context.altitude
        FakeLoc.speed = context.speed
        FakeLoc.enableDebugLog = context.debug
        FakeLoc.disableGetCurrentLocation = context.disableGetCurrentLocation
        FakeLoc.disableRegisterLocationListener = context.disableRegisterLocationListener
        FakeLoc.disableFusedLocation = context.disableFusedProvider
        FakeLoc.needDowngradeToCdma = context.needDowngradeToCdma
        FakeLoc.minSatellites = context.minSatelliteCount
        FakeLoc.enableAGPS = context.enableAGPS
        FakeLoc.enableNMEA = context.enableNMEA
        FakeLoc.disableRequestGeofence = !context.enableRequestGeofence
        FakeLoc.disableGetFromLocation = !context.enableGetFromLocation

        val rely = Bundle()
        rely.putString("command_id", "put_config")
        // Note: Do NOT send "enable" here - it should only be controlled by start/stop commands
        rely.putDouble("altitude", FakeLoc.altitude)
        rely.putDouble("speed", FakeLoc.speed)
        rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
        rely.putBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
        rely.putBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
        rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
        rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
        rely.putInt("min_satellites", FakeLoc.minSatellites)
        rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
        rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
        rely.putBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
        rely.putBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)

        return sendAuthenticated(locationManager, rely, providerName(context))
    }

    fun isServiceInit(): Boolean {
        return randomKey() != null
    }


    private fun startLoopBroadcastLocation(locationManager: LocationManager){
        val appContext = Portal.appContext
        val delayTime=appContext.reportDuration.toLong()

        if(isRunning) return
        if(!appContext.loopBroadcastlocation) return

        isRunning=true
        loopThread=Thread{
            Log.d("MockServiceHelper","loopBoardcast: Start")
            while(isRunning){
                try {
                    broadcastLocation(locationManager)
                    Thread.sleep(delayTime)
                }catch (e:InterruptedException){
                    if (FakeLoc.enableDebugLog) {
                        Log.d("MockServiceHelper","loopBoardcast: Stop")
                    }
                    break
                }
            }
        }
        loopThread!!.start()
    }

    private fun stopLoopBroadcastLocation(){
        isRunning =false
        loopThread?.interrupt()
        loopThread = null
    }


    @SuppressLint("DiscouragedPrivateApi")
    fun loadPortalLibrary(context: Context): Boolean {
        if (!ShellUtils.hasRoot()) return false

        val isX86: Boolean = runCatching {
            if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) {
                return@runCatching true
            }
            val clazz = Class.forName("dalvik.system.VMRuntime")
            val method = clazz.getDeclaredMethod("getRuntime")
            val runtime = method.invoke(null)
            val field = clazz.getDeclaredField("vmInstructionSet")
            field.isAccessible = true
            val instructionSet = field.get(runtime) as String
            if (instructionSet.contains("x86") ) {
                true
            } else false
        }.getOrElse { false }
        // todo: support x86

        val soDir = File("/data/local/portal-lib")
        if (!soDir.exists()) {
            ShellUtils.executeCommand("mkdir ${soDir.absolutePath}")
        }
        val soFile = File(soDir, "libportal.so")
        runCatching {
            val tmpSoFile = File(soDir, "libportal.so.tmp").also { file ->
                var nativeDir = context.applicationInfo.nativeLibraryDir
                val apkSoFile = File(nativeDir, "libportal.so")
                if (apkSoFile.exists()) {
                    ShellUtils.executeCommand("cp ${apkSoFile.absolutePath} ${file.absolutePath}")
                } else {
                    Log.e("MockServiceHelper", "Failed to copy portal library: ${apkSoFile.absolutePath}")
                    return@runCatching
                }
            }
            if (soFile.exists()) {
                val originalHash = ShellUtils.executeCommandToBytes("head -c 4096 ${soFile.absolutePath}")
                val newHash = ShellUtils.executeCommandToBytes("head -c 4096 ${tmpSoFile.absolutePath}")
                if (!originalHash.contentEquals(newHash)) {
                    // 文件内容不同，需要更新
                    ShellUtils.executeCommand("rm ${soFile.absolutePath}")
                    ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
                } else {
                    // 文件内容相同，删除临时文件
                    ShellUtils.executeCommand("rm ${tmpSoFile.absolutePath}")
                }
            } else if (tmpSoFile.exists()) {
                ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
            }
        }.onFailure {
            Log.w("MockServiceHelper", "Failed to copy portal library", it)
        }

        ShellUtils.executeCommand("chmod 777 ${soFile.absolutePath}")

        val result = loadLibrary(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager, soFile.absolutePath)

        Log.d("MockServiceHelper", "load portal library result: $result")

        return result == "success"
    }
}
