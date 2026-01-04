@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package moe.fuqiuluo.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.fuqiuluo.xposed.hooks.BasicLocationHook
import moe.fuqiuluo.xposed.hooks.LocationManagerHook
import moe.fuqiuluo.xposed.hooks.LocationServiceHook
import moe.fuqiuluo.xposed.hooks.fused.AndroidFusedLocationProviderHook
import moe.fuqiuluo.xposed.hooks.fused.ThirdPartyLocationHook
import moe.fuqiuluo.xposed.hooks.oplus.OplusLocationHook
import moe.fuqiuluo.xposed.hooks.telephony.miui.MiuiTelephonyManagerHook
import moe.fuqiuluo.xposed.hooks.sensor.SystemSensorManagerHook
import moe.fuqiuluo.xposed.hooks.telephony.TelephonyHook
import moe.fuqiuluo.xposed.hooks.wlan.WlanHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger

class FakeLocation: IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var cServiceManager: Class<*> // android.os.ServiceManager
    private val mServiceManagerCache by lazy {
        kotlin.runCatching { cServiceManager.getDeclaredField("sCache") }.onSuccess {
            it.isAccessible = true
        }.getOrNull()
        // the field is not guaranteed to exist
    }

    /**
     * Called very early during startup of Zygote.
     * @param startupParam Details about the module itself and the started process.
     * @throws Throwable everything is caught, but will prevent further initialization of the module.
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        if(startupParam == null) return

//        // 宇宙安全声明：以下代码仅供学习交流使用，切勿用于非法用途?
//        System.setProperty("portal.enable", "true")
    }

    /**
     * This method is called when an app is loaded. It's called very early, even before
     * [Application.onCreate] is called.
     * Modules can set up their app-specific hooks here.
     *
     * @param lpparam Information about the app.
     * @throws Throwable Everything the callback throws is caught and logged.
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return

        val packageName = lpparam.packageName

        // Skip certain system packages to avoid issues
        if (packageName == "com.android.systemui") {
            return
        }

        val systemClassLoader = (kotlin.runCatching {
            lpparam.classLoader.loadClass("android.app.ActivityThread")
                ?: Class.forName("android.app.ActivityThread")
        }.onFailure {
            Logger.error("Failed to find ActivityThread", it)
        }.getOrNull() ?: return)
            .getMethod("currentActivityThread")
            .invoke(null)
            .javaClass
            .getClassLoader()

        if (systemClassLoader == null) {
            Logger.error("Failed to get system class loader")
            return
        }

        if(System.getProperty("portal.injected_${lpparam.packageName}") == "true") {
            return
        } else {
            System.setProperty("portal.injected_${lpparam.packageName}", "true")
        }

        when (lpparam.packageName) {
            "com.android.phone" -> {
                Logger.info("Found com.android.phone")
                TelephonyHook(lpparam.classLoader)
                MiuiTelephonyManagerHook(lpparam.classLoader)
            }
            "android" -> {
                Logger.info("Debug Log Status: ${FakeLoc.enableDebugLog}")
                FakeLoc.isSystemServerProcess = true
                startFakeLocHook(systemClassLoader)
                TelephonyHook.hookSubOnTransact(lpparam.classLoader)
                WlanHook(systemClassLoader)
                AndroidFusedLocationProviderHook(lpparam.classLoader)
                SystemSensorManagerHook(lpparam.classLoader)

                ThirdPartyLocationHook(lpparam.classLoader)
            }
            "com.android.location.fused" -> {
                AndroidFusedLocationProviderHook(lpparam.classLoader)
            }
            "com.xiaomi.location.fused" -> {
                ThirdPartyLocationHook(lpparam.classLoader)
            }
            "com.oplus.location" -> {
                OplusLocationHook(lpparam.classLoader)
            }
            else -> {
                // Hook ALL other apps for third-party location SDK support
                // This allows hooking Baidu SDK, Tencent SDK, etc. in their host apps
                hookAppProcess(lpparam, systemClassLoader)
            }
        }
    }

    private fun hookAppProcess(lpparam: XC_LoadPackage.LoadPackageParam, systemClassLoader: ClassLoader) {
        val classLoader = lpparam.classLoader
        val packageName = lpparam.packageName

        Logger.info("Hooking app process: $packageName")

        // Hook android.location.LocationManager in app process
        kotlin.runCatching {
            val cLocationManager = XposedHelpers.findClass("android.location.LocationManager", systemClassLoader)
            LocationManagerHook(cLocationManager)
            Logger.debug("Hooked LocationManager in $packageName")
        }.onFailure {
            Logger.warn("Failed to hook LocationManager in $packageName: ${it.message}")
        }

        // Hook Location.set() and LocationResult for basic interception
        kotlin.runCatching {
            BasicLocationHook(classLoader)
            Logger.debug("Hooked BasicLocationHook in $packageName")
        }.onFailure {
            Logger.warn("Failed to hook BasicLocationHook in $packageName: ${it.message}")
        }

        // Hook third-party location SDKs (Baidu, Tencent, AMap)
        // Note: ThirdPartyLocationHook requires DivineService which may fail in app process
        // but we try anyway in case the portal service is ready
        ThirdPartyLocationHook(classLoader)
    }

    private fun startFakeLocHook(classLoader: ClassLoader) {
        cServiceManager = XposedHelpers.findClass("android.os.ServiceManager", classLoader)

        XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)?.let {
            TelephonyHook.hookTelephonyRegistry(it)
        } // for MUMU emulator

        val cLocationManager =
            XposedHelpers.findClass("android.location.LocationManager", classLoader)

        LocationServiceHook(classLoader)
        LocationManagerHook(cLocationManager)  // intrusive hooks
    }
}