package moe.fuqiuluo.xposed.utils

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Small lifecycle gate for fixed-GPS dispatch.
 *
 * State transitions use one reentrant lock, while dispatch bodies run outside
 * it. The in-flight counter and condition make stop bounded and prevent a
 * previous report from being mixed into a newly begun session.
 */
class FixedGpsSession {
    private val lock = ReentrantLock()
    private val noInFlight = lock.newCondition()
    private val stopDepth = ThreadLocal.withInitial { 0 }
    private val dispatchDepth = ThreadLocal.withInitial { 0 }
    private var active = false
    private var inFlight = 0
    private var stopInProgress = false

    /**
     * Configure and activate this session atomically.
     *
     * [configure] runs while the session lock is held and before activation.
     * If it throws, activation is not committed and the exception propagates.
     */
    fun begin(configure: () -> Unit): Boolean {
        lock.withLock {
            if (active || inFlight != 0 || stopInProgress) {
                return false
            }
            configure()
            active = true
            return true
        }
    }

    /**
     * Run [block] only while the session is active.
     *
     * Only the admission/retirement bookkeeping holds the lock. [block] may
     * perform a system call without blocking lifecycle transitions forever.
     * Exceptions from [block] propagate, while the in-flight count is always
     * decremented and waiters signalled.
     */
    fun runIfActive(block: () -> Unit): Boolean {
        lock.lock()
        try {
            if (!active) {
                return false
            }
            inFlight++
        } finally {
            lock.unlock()
        }

        val previousDispatchDepth = dispatchDepth.get() ?: 0
        dispatchDepth.set(previousDispatchDepth + 1)
        return try {
            block()
            true
        } finally {
            if (previousDispatchDepth == 0) {
                dispatchDepth.remove()
            } else {
                dispatchDepth.set(previousDispatchDepth)
            }
            lock.withLock {
                inFlight--
                if (inFlight == 0) {
                    noInFlight.signalAll()
                }
            }
        }
    }

    /**
     * Close the gate, wait at most [timeoutMillis] for dispatches, then run
     * [cleanup] under the same lock.
     *
     * A same-thread nested stop is rejected before re-entering the lock. A
     * timeout or interruption never runs [cleanup]. Interruption is restored
     * before returning false.
     */
    fun stop(timeoutMillis: Long, cleanup: () -> Unit): Boolean {
        if ((stopDepth.get() ?: 0) != 0 || (dispatchDepth.get() ?: 0) != 0) {
            return false
        }

        stopDepth.set(1)
        var lockHeld = false
        try {
            try {
                lock.lockInterruptibly()
                lockHeld = true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }

            if (stopInProgress) {
                return false
            }
            active = false
            stopInProgress = true

            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0L))
            try {
                while (inFlight != 0) {
                    if (remainingNanos <= 0L) {
                        return false
                    }
                    remainingNanos = try {
                        noInFlight.awaitNanos(remainingNanos)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }

                cleanup()
                return true
            } finally {
                stopInProgress = false
                noInFlight.signalAll()
            }
        } finally {
            if (lockHeld) {
                lock.unlock()
            }
            stopDepth.remove()
        }
    }
}
