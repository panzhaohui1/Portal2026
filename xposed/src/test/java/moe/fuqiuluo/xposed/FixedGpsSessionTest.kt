package moe.fuqiuluo.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import moe.fuqiuluo.xposed.utils.FixedGpsSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedGpsSessionTest {
    @Test
    fun inactiveSessionRejectsDispatch() {
        val session = FixedGpsSession()
        val executed = AtomicBoolean(false)

        assertFalse(session.runIfActive { executed.set(true) })
        assertFalse(executed.get())
    }

    @Test
    fun beginRunsConfigurationBeforeActivationAndActivates() {
        val session = FixedGpsSession()
        var configured = false
        var dispatchAcceptedDuringConfiguration = true

        assertTrue(session.begin {
            configured = true
            dispatchAcceptedDuringConfiguration = session.runIfActive { }
        })

        assertTrue(configured)
        assertFalse(dispatchAcceptedDuringConfiguration)
        assertTrue(session.runIfActive { })
    }

    @Test
    fun beginRejectsWhenAlreadyActiveOrWhenDispatchIsInFlight() {
        val session = FixedGpsSession()
        var activeConfigurationRan = false

        assertTrue(session.begin { })
        assertFalse(session.begin { activeConfigurationRan = true })
        assertFalse(activeConfigurationRan)
        assertTrue(session.stop(1_000) { })

        assertTrue(session.begin { })
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val dispatchFinished = CountDownLatch(1)
        val dispatchThread = daemonThread("fixed-session-begin-guard") {
            session.runIfActive {
                dispatchEntered.countDown()
                releaseDispatch.awaitBounded()
            }
            dispatchFinished.countDown()
        }

        try {
            assertTrue(dispatchEntered.awaitBounded())
            assertFalse(session.stop(50) { })

            var inFlightConfigurationRan = false
            assertFalse(session.begin { inFlightConfigurationRan = true })
            assertFalse(inFlightConfigurationRan)

            releaseDispatch.countDown()
            assertTrue(dispatchFinished.awaitBounded())
            assertTrue(session.stop(1_000) { })
        } finally {
            releaseDispatch.countDown()
            joinBounded(dispatchThread)
        }
    }

    @Test
    fun configurationExceptionDoesNotActivateSession() {
        val session = FixedGpsSession()
        var propagated = false

        try {
            session.begin { error("configuration failure") }
        } catch (error: IllegalStateException) {
            propagated = true
        }

        assertTrue(propagated)
        assertFalse(session.runIfActive { })
        assertTrue(session.begin { })
    }

    @Test
    fun stopClosesGateBeforeCleanupAndRejectsSameThreadReentry() {
        val session = FixedGpsSession()
        var cleanupRan = false
        var nestedStopAccepted = true
        var beginAcceptedDuringCleanup = true
        var dispatchAcceptedDuringCleanup = true

        assertTrue(session.begin { })
        assertTrue(session.stop(1_000) {
            cleanupRan = true
            nestedStopAccepted = session.stop(1_000) { }
            beginAcceptedDuringCleanup = session.begin { }
            dispatchAcceptedDuringCleanup = session.runIfActive { }
        })

        assertTrue(cleanupRan)
        assertFalse(nestedStopAccepted)
        assertFalse(beginAcceptedDuringCleanup)
        assertFalse(dispatchAcceptedDuringCleanup)
        assertFalse(session.runIfActive { })
    }

    @Test
    fun exceptionFromDispatchReleasesInFlightCount() {
        val session = FixedGpsSession()
        var propagated = false
        assertTrue(session.begin { })

        try {
            session.runIfActive { error("dispatch failure") }
        } catch (error: IllegalStateException) {
            propagated = true
        }

        assertTrue(propagated)
        var cleanupRan = false
        assertTrue(session.stop(1_000) { cleanupRan = true })
        assertTrue(cleanupRan)
    }

    @Test
    fun parallelDispatchesCanRunOutsideTheLifecycleLock() {
        val session = FixedGpsSession()
        assertTrue(session.begin { })

        val dispatchesEntered = CountDownLatch(2)
        val releaseDispatches = CountDownLatch(1)
        val dispatchesFinished = CountDownLatch(2)
        val firstAccepted = AtomicBoolean(false)
        val secondAccepted = AtomicBoolean(false)

        val first = daemonThread("fixed-session-parallel-1") {
            firstAccepted.set(session.runIfActive {
                dispatchesEntered.countDown()
                releaseDispatches.awaitBounded()
            })
            dispatchesFinished.countDown()
        }
        val second = daemonThread("fixed-session-parallel-2") {
            secondAccepted.set(session.runIfActive {
                dispatchesEntered.countDown()
                releaseDispatches.awaitBounded()
            })
            dispatchesFinished.countDown()
        }

        try {
            assertTrue(dispatchesEntered.awaitBounded())
            releaseDispatches.countDown()
            assertTrue(dispatchesFinished.awaitBounded())
            assertTrue(firstAccepted.get())
            assertTrue(secondAccepted.get())
            assertTrue(session.stop(1_000) { })
        } finally {
            releaseDispatches.countDown()
            joinBounded(first)
            joinBounded(second)
        }
    }

    @Test
    fun stopWaitsForDispatchAndRejectsDispatchAfterCleanup() {
        val session = FixedGpsSession()
        assertTrue(session.begin { })

        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val dispatchBlockCompleted = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val cleanupSawDispatchComplete = AtomicBoolean(false)
        val stopSucceeded = AtomicBoolean(false)
        val dispatchAccepted = AtomicBoolean(false)
        val dispatchReturned = CountDownLatch(1)

        val dispatchThread = daemonThread("fixed-session-dispatch") {
            try {
                dispatchAccepted.set(
                    session.runIfActive {
                        dispatchEntered.countDown()
                        releaseDispatch.awaitBounded()
                        dispatchBlockCompleted.countDown()
                    },
                )
            } finally {
                dispatchReturned.countDown()
            }
        }

        assertTrue(dispatchEntered.awaitBounded())

        val stopThread = daemonThread("fixed-session-stop") {
            stopAttempted.countDown()
            stopSucceeded.set(session.stop(2_000) {
                cleanupSawDispatchComplete.set(dispatchBlockCompleted.awaitBounded())
                cleanupEntered.countDown()
                releaseCleanup.awaitBounded()
            })
            stopFinished.countDown()
        }

        var lateDispatchThread: Thread? = null
        try {
            assertTrue(stopAttempted.awaitBounded())
            assertFalse(stopFinished.await(100, TimeUnit.MILLISECONDS))
            assertFalse(cleanupEntered.await(100, TimeUnit.MILLISECONDS))

            releaseDispatch.countDown()
            assertTrue(dispatchBlockCompleted.awaitBounded())
            assertTrue(dispatchReturned.awaitBounded())
            assertTrue(dispatchAccepted.get())
            assertTrue(cleanupEntered.awaitBounded())
            assertFalse(stopFinished.await(100, TimeUnit.MILLISECONDS))
            releaseCleanup.countDown()

            assertTrue(stopFinished.awaitBounded())
            assertTrue(stopSucceeded.get())
            assertTrue(cleanupSawDispatchComplete.get())

            val lateDispatchAccepted = AtomicBoolean(true)
            val lateDispatchFinished = CountDownLatch(1)
            lateDispatchThread = daemonThread("fixed-session-late-dispatch") {
                lateDispatchAccepted.set(session.runIfActive { })
                lateDispatchFinished.countDown()
            }
            assertTrue(lateDispatchFinished.awaitBounded())
            assertFalse(lateDispatchAccepted.get())
            assertFalse(session.runIfActive { })

            joinBounded(dispatchThread)
            joinBounded(stopThread)
        } finally {
            releaseDispatch.countDown()
            releaseCleanup.countDown()
            joinBounded(dispatchThread)
            joinBounded(stopThread)
            lateDispatchThread?.let(::joinBounded)
        }
    }

    @Test
    fun concurrentStopsHaveOneOwnerAndOneCleanup() {
        val session = FixedGpsSession()
        assertTrue(session.begin { })

        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val dispatchFinished = CountDownLatch(1)
        val stopReady = CountDownLatch(2)
        val startStops = CountDownLatch(1)
        val stopsFinished = CountDownLatch(2)
        val successfulStops = AtomicInteger(0)
        val cleanupCount = AtomicInteger(0)

        val dispatchThread = daemonThread("fixed-session-concurrent-dispatch") {
            session.runIfActive {
                dispatchEntered.countDown()
                releaseDispatch.awaitBounded()
            }
            dispatchFinished.countDown()
        }

        fun stopWorker(name: String): Thread = daemonThread(name) {
            stopReady.countDown()
            startStops.awaitBounded()
            if (session.stop(2_000) { cleanupCount.incrementAndGet() }) {
                successfulStops.incrementAndGet()
            }
            stopsFinished.countDown()
        }

        val firstStopThread = stopWorker("fixed-session-concurrent-stop-1")
        val secondStopThread = stopWorker("fixed-session-concurrent-stop-2")
        try {
            assertTrue(dispatchEntered.awaitBounded())
            assertTrue(stopReady.awaitBounded())
            startStops.countDown()
            assertFalse(stopsFinished.await(100, TimeUnit.MILLISECONDS))

            releaseDispatch.countDown()
            assertTrue(dispatchFinished.awaitBounded())
            assertTrue(stopsFinished.awaitBounded())
            assertEquals(1, successfulStops.get())
            assertEquals(1, cleanupCount.get())
            assertFalse(session.runIfActive { })
        } finally {
            releaseDispatch.countDown()
            startStops.countDown()
            joinBounded(dispatchThread)
            joinBounded(firstStopThread)
            joinBounded(secondStopThread)
        }
    }

    @Test
    fun timeoutSkipsCleanupAndStopRetrySucceedsAfterDispatchCompletes() {
        val session = FixedGpsSession()
        assertTrue(session.begin { })

        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val dispatchFinished = CountDownLatch(1)
        val dispatchThread = daemonThread("fixed-session-timeout") {
            session.runIfActive {
                dispatchEntered.countDown()
                releaseDispatch.awaitBounded()
            }
            dispatchFinished.countDown()
        }

        try {
            assertTrue(dispatchEntered.awaitBounded())
            var cleanupRan = false
            assertFalse(session.stop(50) { cleanupRan = true })
            assertFalse(cleanupRan)

            var beginConfigurationRan = false
            assertFalse(session.begin { beginConfigurationRan = true })
            assertFalse(beginConfigurationRan)

            releaseDispatch.countDown()
            assertTrue(dispatchFinished.awaitBounded())
            joinBounded(dispatchThread)

            assertTrue(session.stop(1_000) { cleanupRan = true })
            assertTrue(cleanupRan)
            assertFalse(session.runIfActive { })
        } finally {
            releaseDispatch.countDown()
            joinBounded(dispatchThread)
        }
    }

    @Test
    fun interruptedStopRestoresInterruptAndDoesNotCleanup() {
        val session = FixedGpsSession()
        assertTrue(session.begin { })

        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val dispatchFinished = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val cleanupRan = AtomicBoolean(false)
        val stopSucceeded = AtomicBoolean(true)
        val interruptRestored = AtomicBoolean(false)

        val dispatchThread = daemonThread("fixed-session-interrupt-dispatch") {
            session.runIfActive {
                dispatchEntered.countDown()
                releaseDispatch.awaitBounded()
            }
            dispatchFinished.countDown()
        }
        val stopThread = daemonThread("fixed-session-interrupt-stop") {
            stopAttempted.countDown()
            stopSucceeded.set(session.stop(10_000) { cleanupRan.set(true) })
            interruptRestored.set(Thread.currentThread().isInterrupted)
            stopFinished.countDown()
        }

        try {
            assertTrue(dispatchEntered.awaitBounded())
            assertTrue(stopAttempted.awaitBounded())
            assertFalse(stopFinished.await(100, TimeUnit.MILLISECONDS))

            stopThread.interrupt()
            assertTrue(stopFinished.awaitBounded())
            assertFalse(stopSucceeded.get())
            assertTrue(interruptRestored.get())
            assertFalse(cleanupRan.get())

            releaseDispatch.countDown()
            assertTrue(dispatchFinished.awaitBounded())
            assertTrue(session.stop(1_000) { })
        } finally {
            releaseDispatch.countDown()
            joinBounded(dispatchThread)
            joinBounded(stopThread)
        }
    }

    private fun daemonThread(name: String, block: () -> Unit): Thread {
        return thread(start = true, isDaemon = true, name = name, block = block)
    }

    private fun CountDownLatch.awaitBounded(): Boolean {
        return await(2, TimeUnit.SECONDS)
    }

    private fun joinBounded(thread: Thread) {
        thread.join(2_000)
        assertFalse("test thread did not terminate", thread.isAlive)
    }
}
