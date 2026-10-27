package moe.fuqiuluo.xposed.hooks

import android.location.Location
import android.location.LocationManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.hooks.blindhook.BlindHookLocation
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.LocationSanitizationGuard
import moe.fuqiuluo.xposed.utils.hookAllMethodsAfter
import moe.fuqiuluo.xposed.utils.hookAllMethodsBefore
import moe.fuqiuluo.xposed.utils.toClass
import moe.fuqiuluo.xposed.utils.toClassOrThrow
import kotlin.random.Random

object BasicLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
//        val hookSetLatitude = object: XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//                if (!FakeLocationConfig.enable) return
//
//            }
//        }
//        val hookSetLongitude = object: XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//
//
//            }
//        }
//        XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "setLatitude", Double::class.java, hookSetLatitude)
//        XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "setLongitude", Double::class.java, hookSetLongitude)

        kotlin.runCatching {
            val cLocationResult = "android.location.LocationResult".toClassOrThrow(classLoader)
            BlindHookLocation(cLocationResult, classLoader)

            cLocationResult.hookAllMethodsAfter("asList") {
                if (!FakeLoc.enable) return@hookAllMethodsAfter

                val locations = result as? List<*> ?: emptyList<Any?>()
                result = locations.mapNotNull { it as? Location }.map { injectLocation(it) }
            }

            cLocationResult.hookAllMethodsBefore("writeToParcel") {
                if (!FakeLoc.enable) return@hookAllMethodsBefore

                val locationResult = thisObject
                val mLocationsField = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
                if (mLocationsField == null) {
                    Logger.error("Failed to find mLocations in LocationResult")
                    result = null
                    return@hookAllMethodsBefore
                }
                if (kotlin.runCatching { mLocationsField.isAccessible = true }.isFailure) {
                    result = null
                    return@hookAllMethodsBefore
                }
                val mLocations = kotlin.runCatching {
                    mLocationsField.get(locationResult) as? List<*> ?: emptyList<Any?>()
                }.getOrElse {
                    result = null
                    Logger.error("Failed to read LocationResult locations", it)
                    return@hookAllMethodsBefore
                }

                val sanitizedLocations = mLocations.mapNotNull { it as? Location }
                    .map { injectLocation(it) }
                    .ifEmpty { listOf(injectLocation(Location(LocationManager.GPS_PROVIDER))) }

                kotlin.runCatching {
                    mLocationsField.set(locationResult, ArrayList(sanitizedLocations))
                }.onFailure {
                    result = null
                    Logger.error("Failed to write sanitized LocationResult", it)
                }
            }
        }.onFailure {
           Logger.error("Failed to hook LocationResult", it)
        }

        Location::class.java.hookAllMethodsBefore("set") {
            if (!FakeLoc.enable || LocationSanitizationGuard.isActive()) return@hookAllMethodsBefore
            val source = args.getOrNull(0) as? Location ?: run {
                result = null
                return@hookAllMethodsBefore
            }
            args[0] = injectLocation(source)
        }
//        if (FakeLocationConfig.DEBUG) {
//            // Track the invocation of AutoNavi map system services
//            val cBundle = XposedHelpers.findClass("android.os.Bundle", classLoader)
//            XposedBridge.hookAllMethods(cBundle, "putInt", object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    val key = param.args[0] as? String
//                    val value = param.args[1] as Int
//
//                    if (key == "amap" || key == "resubtype" || key == "maxCn0") {
//                        XposedBridge.log(RuntimeException())
//                    }
//                }
//            })
//            XposedBridge.hookAllMethods(Location::class.java, "setExtras", object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    XposedBridge.log(RuntimeException())
//                }
//            })
//        }


    }
}
