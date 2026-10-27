package moe.fuqiuluo.xposed.utils

/** Tracks internal location construction separately for each thread. */
object LocationSanitizationGuard {
    private val depth = ThreadLocal<Int>()

    fun isActive(): Boolean = (depth.get() ?: 0) > 0

    fun <T> withSanitization(block: () -> T): T {
        val previous = depth.get() ?: 0
        depth.set(previous + 1)
        return try {
            block()
        } finally {
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }
}
