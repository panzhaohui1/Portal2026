package moe.fuqiuluo.xposed.hooks.fused

import android.location.Location
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.onceHookMethodBefore
import moe.fuqiuluo.xposed.utils.toClass

/**
 * Targeted hook for Xiaomi FusedLocationProvider v3.0.2.
 *
 * The target is deliberately limited to the verified private method
 * com.xiaomi.location.fused.b#a(Location). No other Xiaomi methods are
 * inferred or hooked here.
 */
object XiaomiFusedLocationHook : BaseLocationHook() {
    private const val TARGET_CLASS = "com.xiaomi.location.fused.b"
    private const val TARGET_METHOD = "a"

    operator fun invoke(classLoader: ClassLoader) {
        val targetClass = TARGET_CLASS.toClass(classLoader)
        if (targetClass == null) {
            Logger.error("Failed to find Xiaomi fused target class")
            return
        }

        val targetMethod = runCatching {
            targetClass.getDeclaredMethod(TARGET_METHOD, Location::class.java)
        }.getOrNull()
        if (targetMethod == null || targetMethod.returnType != Void.TYPE) {
            Logger.error("Failed to verify Xiaomi fused target method")
            return
        }

        val hook = runCatching {
            targetClass.onceHookMethodBefore(TARGET_METHOD, Location::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore

                val sourceLocation = args.firstOrNull() as? Location
                if (sourceLocation == null) {
                    result = null
                    Logger.error("Skipped Xiaomi fused report without a Location")
                    return@onceHookMethodBefore
                }

                val replacement = runCatching {
                    injectLocation(sourceLocation)
                }.getOrNull()

                // If the switch was turned off during injection, preserve the
                // original behavior. Otherwise never pass the source object on.
                if (!FakeLoc.enable) return@onceHookMethodBefore
                if (replacement == null || replacement === sourceLocation) {
                    result = null
                    Logger.error("Skipped Xiaomi fused report after replacement failure")
                    return@onceHookMethodBefore
                }
                args[0] = replacement
            }
        }.getOrNull()

        if (hook == null) {
            Logger.error("Failed to install Xiaomi fused location hook")
            return
        }

        if (!initDivineService("XiaomiFusedLocationHook")) {
            Logger.error("Failed to init DivineService in XiaomiFusedLocationHook")
        }
    }
}
