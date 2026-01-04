package moe.fuqiuluo.xposed.utils

import de.robv.android.xposed.XposedBridge

object Logger {
    private fun isEnableLog(): Boolean {
        return FakeLoc.enableLog
    }

    fun info(msg: String) {
        if (isEnableLog()) {
            XposedBridge.log("[L-System] $msg")
        }
    }

    fun info(msg: String, throwable: Throwable) {
        if (isEnableLog()) {
            XposedBridge.log("[L-System] $msg: ${throwable.stackTraceToString()}")
        }
    }

    fun debug(msg: String) {
        XposedBridge.log("[L-System][DEBUG] $msg")
    }

    fun debug(msg: String, throwable: Throwable) {
        XposedBridge.log("[L-System][DEBUG] $msg: ${throwable.stackTraceToString()}")
    }

    fun error(msg: String) {
        XposedBridge.log("[L-System][ERROR] $msg")
    }

    fun error(msg: String, throwable: Throwable) {
        XposedBridge.log("[L-System][ERROR] $msg: ${throwable.stackTraceToString()}")
    }

    fun warn(msg: String) {
        XposedBridge.log("[L-System][WARN] $msg")
    }

    fun warn(msg: String, throwable: Throwable) {
        XposedBridge.log("[L-System][WARN] $msg: ${throwable.stackTraceToString()}")
    }
}