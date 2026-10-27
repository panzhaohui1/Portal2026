package moe.fuqiuluo.xposed.hooks.provider

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.util.ArrayMap
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.hooks.BasicLocationHook.injectLocation
import moe.fuqiuluo.xposed.hooks.blindhook.BlindHookLocation
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.diyHook
import moe.fuqiuluo.xposed.utils.hook
import moe.fuqiuluo.xposed.utils.onceHook
import moe.fuqiuluo.xposed.utils.onceHookAllMethod
import moe.fuqiuluo.xposed.utils.onceHookMethodBefore
import java.lang.reflect.Method
import java.util.Collections
import kotlin.random.Random

object LocationProviderManagerHook {
    @Volatile
    private var systemReportHookReady = false
    private var installedSystemReportMethod: Method? = null
    private var installedGpsCurrentMethod: Method? = null
    private var installedGpsRegistrationMethod: Method? = null

    fun isSystemReportHookReady(): Boolean = systemReportHookReady

    private val hookOnFetchLocationResult = beforeHook {
        if (args.isEmpty()) return@beforeHook
        if (!FakeLoc.enable) return@beforeHook

        if (FakeLoc.enableDebugLog) {
            Logger.debug("${method}: injected!")
        }

        val locationResult = args[0] ?: run {
            result = null
            return@beforeHook
        }
        val mLocationsField = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
        if (mLocationsField == null) {
            Logger.error("Failed to find mLocations in LocationResult")
            result = null
            return@beforeHook
        }
        if (kotlin.runCatching { mLocationsField.isAccessible = true }.isFailure) {
            result = null
            return@beforeHook
        }
        val mLocations = kotlin.runCatching {
            mLocationsField.get(locationResult) as? List<*> ?: emptyList<Any?>()
        }.getOrElse {
            result = null
            Logger.error("Failed to read LocationResult locations", it)
            return@beforeHook
        }

        val sanitizedLocations = runCatching {
            mLocations.mapNotNull { it as? Location }.map { injectLocation(it) }
        }.getOrElse {
            result = null
            Logger.error("Failed to sanitize LocationResult")
            return@beforeHook
        }
        if (sanitizedLocations.isEmpty()) {
            result = null
            return@beforeHook
        }

        kotlin.runCatching {
            mLocationsField.set(locationResult, ArrayList(sanitizedLocations))
        }.onFailure {
            result = null
            Logger.error("Failed to write sanitized LocationResult", it)
        }
    }

    @Synchronized
    operator fun invoke(classLoader: ClassLoader) {
        systemReportHookReady = false
        val managerHookReady = hookLocationProviderManager(classLoader)
        hookDelegateLocationProvider(classLoader)
        hookPassiveLocationProvider(classLoader)
        hookProxyLocationProvider(classLoader)
        hookAbstractLocationProvider(classLoader)
        hookOtherProvider(classLoader)
        hookGeofenceProvider(classLoader)
        systemReportHookReady = managerHookReady
    }

    private fun hookAbstractLocationProvider(classLoader: ClassLoader) {
        run {
            val cAbstractLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.AbstractLocationProvider", classLoader)
                ?: return@run
            val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                ?: return@run
            val mReportLocation = XposedHelpers.findMethodExactIfExists(cAbstractLocationProvider, "reportLocation", cLocationResult)
                ?: return@run

            mReportLocation.onceHook(hookOnFetchLocationResult)
        }

        run {
            val cInternalState = XposedHelpers.findClassIfExists("com.android.server.location.provider.AbstractLocationProvider\$InternalState", classLoader)
                ?: return@run

            XposedBridge.hookAllConstructors(cInternalState, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] ?: return

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("AbstractLocationProvider.InternalState: injected!")
                    }

                    // will hook class AbstractLocationProvider.Listener, Be careful not to repeat the hooker!
                    listener.javaClass.onceHookAllMethod("onReportLocation", hookOnFetchLocationResult)
                }
            })
        }

    }

    private fun hookProxyLocationProvider(classLoader: ClassLoader) {
        val cProxyLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.proxy.ProxyLocationProvider", classLoader)
            ?: return


    }

    private fun hookPassiveLocationProvider(classLoader: ClassLoader) {
        val cPassiveLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.PassiveLocationProvider", classLoader)
            ?: return
        val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
            ?: return
        val updateLocation = XposedHelpers.findMethodExactIfExists(cPassiveLocationProvider, "updateLocation", cLocationResult)
            ?: return

        updateLocation.hook(hookOnFetchLocationResult)
    }

    private fun hookDelegateLocationProvider(classLoader: ClassLoader) {
        val cDelegateLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.DelegateLocationProvider", classLoader)
            ?: return

        val waitForInitialization = XposedHelpers.findMethodExactIfExists(cDelegateLocationProvider, "waitForInitialization") ?: return
        waitForInitialization.diyHook(
            hookOnce = true,
            before = {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("DelegateLocationProvider.waitForInitialization: injected!")
                }

                val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                    ?: return@diyHook true
                XposedHelpers.findMethodExactIfExists(thisObject.javaClass, "onReportLocation", cLocationResult)?.onceHook(hookOnFetchLocationResult)
                XposedHelpers.findMethodExactIfExists(thisObject.javaClass, "reportLocation", cLocationResult)?.onceHook(hookOnFetchLocationResult)

                return@diyHook true
            }
        )
    }

    private fun hookLocationProviderManager(classLoader: ClassLoader): Boolean {
        val cLocationProviderManager = XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager", classLoader)
            ?: return false
        val requestClass = XposedHelpers.findClassIfExists("android.location.LocationRequest", classLoader)
        val identityClass = XposedHelpers.findClassIfExists("android.location.util.identity.CallerIdentity", classLoader)
        val callbackClass = XposedHelpers.findClassIfExists("android.location.ILocationCallback", classLoader)
        val listenerClass = XposedHelpers.findClassIfExists("android.location.ILocationListener", classLoader)
        val expectedCurrent = if (requestClass != null && identityClass != null && callbackClass != null) {
            XposedHelpers.findMethodExactIfExists(
                cLocationProviderManager, "getCurrentLocation", requestClass, identityClass,
                Int::class.javaPrimitiveType, callbackClass,
            )
        } else null
        val expectedRegistration = if (requestClass != null && identityClass != null && listenerClass != null) {
            XposedHelpers.findMethodExactIfExists(
                cLocationProviderManager, "registerLocationRequest", requestClass, identityClass,
                Int::class.javaPrimitiveType, listenerClass,
            )
        } else null
        BlindHookLocation(cLocationProviderManager, classLoader)

        XposedBridge.hookAllMethods(cLocationProviderManager, "setRealProvider", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("setRealProvider called")
                }
            }
        })
        XposedBridge.hookAllMethods(cLocationProviderManager, "setMockProvider", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("setMockProvider called")
                }
            }
        })
        XposedBridge.hookAllMethods(cLocationProviderManager, "sendExtraCommand", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if(param.args.size < 4) return
                val command = param.args[2]

                if (FakeLoc.enable && (command == "force_xtra_injection" || command == "CMD_SHOW_GPS_TIPS_CONFIG")) {
                    param.result = null
                    return
                }

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("sendExtraCommand received: $command")
                }
            }
        })

        run {
            val hookedListeners = Collections.synchronizedSet(mutableSetOf<Class<*>>())
            val currentHooks = cLocationProviderManager.onceHookAllMethod("getCurrentLocation", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size < 4 || param.args[3] == null) return

                    val callback = param.args[3]
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("getCurrentLocation callback hooked")
                    }

                    if (FakeLoc.enable && FakeLoc.disableGetCurrentLocation) {
                        param.result = null
                        return
                    }

                    val classCallback = callback.javaClass
                    synchronized(hookedListeners) {
                        if (hookedListeners.contains(classCallback)) return
                        val installed = runCatching { XposedBridge.hookAllMethods(classCallback, "onLocation", object: XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam?) {
                            if (param == null || param.args.isEmpty()) return
                            val location = param.args[0] as? Location ?: run {
                                param.args[0] = null
                                return
                            }

                            if (FakeLoc.enableDebugLog)
                                Logger.debug("onLocation(LocationProviderManager.getCurrentLocation): injected!")
                            param.args[0] = injectLocation(location)
                        }
                        }).isNotEmpty() }.getOrDefault(false)
                        if (!installed) {
                            Logger.error("hook onLocation(LocationProviderManager.getCurrentLocation) failed")
                            if (FakeLoc.enable) param.result = null
                            return
                        }

                        hookedListeners.add(classCallback)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!param.hasThrowable() && param.result != null) {
                        FixedGpsDispatcher.watch(param.thisObject)
                    }
                }
            })
            if (expectedCurrent != null && currentHooks.any { it.hookedMethod == expectedCurrent }) {
                installedGpsCurrentMethod = expectedCurrent
            }
            if (installedGpsCurrentMethod != expectedCurrent || expectedCurrent == null) {
                Logger.error("hook LocationProviderManager.getCurrentLocation failed")
            }
        }

        val registrationHooks = cLocationProviderManager.onceHookAllMethod("registerLocationRequest", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!param.hasThrowable()) {
                        FixedGpsDispatcher.watch(param.thisObject)
                    }
                }
            })
        if (expectedRegistration != null && registrationHooks.any { it.hookedMethod == expectedRegistration }) {
            installedGpsRegistrationMethod = expectedRegistration
        }
        if (installedGpsRegistrationMethod != expectedRegistration || expectedRegistration == null) {
            Logger.warn("Fixed GPS registration observer unavailable")
        }

        // The fixed-mode start gate requires this exact void system method;
        // class or hook installation failures must never be reported as ready.
        val resultClass = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
        val reportMethod = resultClass?.let {
            XposedHelpers.findMethodExactIfExists(cLocationProviderManager, "onReportLocation", it)
        }
        if (reportMethod != null && reportMethod.returnType == Void.TYPE) {
            if (installedSystemReportMethod != reportMethod &&
                reportMethod.onceHook(hookOnFetchLocationResult) != null
            ) {
                installedSystemReportMethod = reportMethod
            }
        }
        val reportReady = reportMethod != null && installedSystemReportMethod == reportMethod
        val experimentalReady = !FixedGpsDispatcher.isVerifiedTestDevice() ||
            (expectedCurrent != null && installedGpsCurrentMethod == expectedCurrent &&
                expectedRegistration != null && installedGpsRegistrationMethod == expectedRegistration &&
                FixedGpsDispatcher.hasRequiredSystemInterfaces(cLocationProviderManager, classLoader))
        val ready = reportReady && experimentalReady
        if (!ready) {
            Logger.error("Required system location report hook is not ready")
        }
        return ready

    }

    private fun hookGeofenceProvider(classLoader: ClassLoader) {
        val cGeofenceManager = XposedHelpers.findClassIfExists("com.android.server.geofence.GeofenceManager", classLoader)
            ?: return
        BlindHookLocation(cGeofenceManager, classLoader)
    }

    private fun hookOtherProvider(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cGnssLocationProvider = XposedHelpers.findClassIfExists("com.android.location.provider.LocationProviderBase", classLoader)
                ?: return@runCatching
            if(BlindHookLocation(cGnssLocationProvider, classLoader) == 0) {
                cGnssLocationProvider.onceHookMethodBefore("reportLocation", Location::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("LocationProviderBase.reportLocation: injected!")
                    }
                    args[0] = (args[0] as? Location)?.let { injectLocation(it) }
                }
            }
            cGnssLocationProvider.onceHookMethodBefore("reportLocations", List::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("LocationProviderBase.reportLocations: injected!")
                }
                args[0] = (args[0] as? List<*>)
                    ?.mapNotNull { it as? Location }
                    ?.map { injectLocation(it) }
                    ?: emptyList<Location>()
            }
        }.onFailure {
            Logger.warn("Failed to hook LocationProviderBase", it)
        }

        kotlin.runCatching {
            val cGnssLocationProvider = XposedHelpers.findClass("com.android.server.location.gnss.GnssLocationProvider", classLoader)
            cGnssLocationProvider.onceHookMethodBefore("onReportLocation", Boolean::class.java, Location::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore

                args[1] = (args[1] as? Location)?.let { injectLocation(it) }

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GnssLocationProvider.onReportLocation: injected")
                }
            }

            cGnssLocationProvider.onceHookMethodBefore("onReportLocations", Boolean::class.java, Array<Location>::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore

                args[0] = (args[0] as? Array<*>)
                    ?.mapNotNull { it as? Location }
                    ?.map { injectLocation(it) }
                    ?.toTypedArray()
                    ?: emptyArray<Location>()

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GnssLocationProvider.onReportLocations: injected")
                }
            }

            cGnssLocationProvider.onceHookMethodBefore("getCellType", CellInfo::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GnssLocationProvider.getCellType: injected!")
                }

                result = 0
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cGnssLocationProvider.onceHookMethodBefore("getCidFromCellIdentity", CellIdentity::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("GnssLocationProvider.getCidFromCellIdentity: injected!")
                    }

                    result = -1L
                }

                cGnssLocationProvider.onceHookMethodBefore("setRefLocation", Int::class.java, CellIdentity::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("GnssLocationProvider.setRefLocation: injected!")
                    }

                    args[0] = 114514 // disable AGPS
                }
            }
        }.onFailure {
            Logger.warn("Failed to hook GnssLocationProvider", it)
        }
    }
}
