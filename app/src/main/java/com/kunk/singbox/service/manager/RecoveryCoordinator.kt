package com.kunk.singbox.service.manager

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Single entry point for all recovery/reset/restart operations.
 *
 * Goals (stability-first):
 * - Serialize operations (no concurrent recover/reset/restart calls into libbox)
 * - Coalesce bursts (screen on + foreground + network change, etc.) into one action
 * - Apply cooldown to the most disruptive operations (restart/deep)
 * - Provide consistent observability
 */
class RecoveryCoordinator(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "RecoveryCoordinator"

        private const val COALESCE_WINDOW_MS = 2000L
        private const val RESTART_COOLDOWN_MS = 120000L
        private const val DEEP_COOLDOWN_MS = 30000L

        private const val MAX_REASON_LEN = 240
    }

    interface Callbacks {
        val isRunning: Boolean
        val isStopping: Boolean

        suspend fun recoverNetwork(mode: Int, reason: String): Boolean
        suspend fun enterDeviceIdle(reason: String): Boolean
        suspend fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean)
        suspend fun resetCoreNetwork(reason: String, force: Boolean)
        suspend fun restartVpnService(reason: String)

        fun addLog(message: String)
    }

    private var callbacks: Callbacks? = null

    private val lastRestartAtMs = AtomicLong(0L)
    private val lastDeepAtMs = AtomicLong(0L)

    private val stateLock = Any()
    private var pending: Request? = null
    private var worker: Job? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun cleanup() {
        synchronized(stateLock) {
            pending = null
            worker?.cancel()
            worker = null
        }
        callbacks = null
    }

    /**
     * Enqueue a recovery request. Requests are coalesced and executed serially.
     */
    fun request(req: Request) {
        val now = SystemClock.elapsedRealtime()
        val startWorker: Boolean
        synchronized(stateLock) {
            pending = when (val current = pending) {
                null -> req
                else -> merge(current, req, now)
            }
            startWorker = worker?.isActive != true
            if (startWorker) {
                worker = scope.launch {
                    drainLoop()
                }
            }
        }
    }

    private suspend fun drainLoop() {
        while (true) {
            val req = synchronized(stateLock) {
                val r = pending
                pending = null
                r
            } ?: return

            execute(req)
        }
    }

    private fun merge(a: Request, b: Request, nowMs: Long): Request {
        // Within a short window, merge aggressively.
        val withinWindow = (nowMs - a.requestedAtMs) <= COALESCE_WINDOW_MS || (nowMs - b.requestedAtMs) <= COALESCE_WINDOW_MS
        if (!withinWindow) {
            // Not a burst: keep the earlier request and let the later one be handled next.
            // We still keep single pending slot; prefer higher priority to reduce churn.
        }

        // If doze-enter is involved, always prefer it (stability-first: stop churn).
        if (a is Request.EnterDeviceIdle || b is Request.EnterDeviceIdle) {
            val chosen = if (b.priority > a.priority) b else a
            val other = if (chosen === b) a else b
            return chosen.withReason(mergeReason(chosen.reason, other.reason))
        }

        // If both are reset-style requests, keep BOTH (common during network switching).
        val mergedReason = mergeReason(a.reason, b.reason)
        val resetComposite = mergeResetRequests(a, b, mergedReason)
        if (resetComposite != null) return resetComposite

        val chosen = if (b.priority > a.priority) b else a
        val other = if (chosen === b) a else b

        return chosen.withReason(mergeReason(chosen.reason, other.reason))
    }

    private fun mergeResetRequests(a: Request, b: Request, mergedReason: String): Request? {
        if (!isResetish(a) || !isResetish(b)) return null

        val items = mutableListOf<Request>()
        fun addReset(req: Request) {
            when (req) {
                is Request.ResetConnections,
                is Request.ResetCoreNetwork -> items.add(req)
                is Request.Composite -> {
                    req.items.forEach { addReset(it) }
                }
                else -> Unit
            }
        }

        addReset(a)
        addReset(b)

        val hasResetConnections = items.any { it is Request.ResetConnections }
        val hasResetCoreNetwork = items.any { it is Request.ResetCoreNetwork }
        if (!hasResetConnections || !hasResetCoreNetwork) return null

        // De-dup: keep one of each type (prefer the higher-priority ResetCoreNetwork if multiple).
        val core = items.filterIsInstance<Request.ResetCoreNetwork>()
            .maxByOrNull { it.priority }
        val conn = items.filterIsInstance<Request.ResetConnections>()
            .maxByOrNull { it.priority }
        if (core == null || conn == null) return null

        return Request.Composite(
            items = listOf(core, conn),
            reason = mergedReason,
            requestedAtMs = minOf(core.requestedAtMs, conn.requestedAtMs)
        )
    }

    private fun isResetish(req: Request): Boolean {
        return when (req) {
            is Request.ResetConnections,
            is Request.ResetCoreNetwork -> true
            is Request.Composite -> req.items.all {
                it is Request.ResetConnections || it is Request.ResetCoreNetwork
            }
            else -> false
        }
    }

    private fun mergeReason(primary: String, secondary: String): String {
        if (secondary.isBlank() || secondary == primary) return primary
        val merged = "$primary | $secondary"
        return if (merged.length <= MAX_REASON_LEN) merged else merged.take(MAX_REASON_LEN)
    }

    private suspend fun execute(req: Request) {
        if (req is Request.Composite) {
            // Execute in descending priority order (e.g. reset network first, then close connections).
            req.items.sortedByDescending { it.priority }.forEach { execute(it) }
            return
        }
        val cb = callbacks ?: return

        // Gate: avoid touching core when not running/stopping.
        // Restart is handled separately below.
        if (req !is Request.Restart && (!cb.isRunning || cb.isStopping)) {
            Log.d(TAG, "Skip ${req.javaClass.simpleName}: running=${cb.isRunning} stopping=${cb.isStopping} reason=${req.reason}")
            return
        }

        val now = SystemClock.elapsedRealtime()
        when (req) {
            is Request.Restart -> {
                if (!cb.isRunning || cb.isStopping) {
                    Log.w(TAG, "Skip restart: running=${cb.isRunning} stopping=${cb.isStopping} reason=${req.reason}")
                    cb.addLog("INFO [Recovery] restart skipped (state) reason=${req.reason}")
                    return
                }
                val last = lastRestartAtMs.get()
                if (now - last < RESTART_COOLDOWN_MS) {
                    Log.w(TAG, "Restart in cooldown, skip. reason=${req.reason}")
                    cb.addLog("INFO [Recovery] restart skipped (cooldown) reason=${req.reason}")
                    return
                }
                lastRestartAtMs.set(now)
            }

            is Request.Recover -> {
                if (req.mode == 3) {
                    val last = lastDeepAtMs.get()
                    if (now - last < DEEP_COOLDOWN_MS) {
                        Log.w(TAG, "Deep recovery in cooldown, skip. reason=${req.reason}")
                        cb.addLog("INFO [Recovery] deep skipped (cooldown) reason=${req.reason}")
                        return
                    }
                    lastDeepAtMs.set(now)
                }
            }

            else -> Unit
        }

        val start = SystemClock.elapsedRealtime()
        try {
            when (req) {
                is Request.Recover -> {
                    val ok = cb.recoverNetwork(req.mode, req.reason)
                    cb.addLog("INFO [Recovery] recover(mode=${req.mode}) ok=$ok cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}")
                }

                is Request.EnterDeviceIdle -> {
                    val ok = cb.enterDeviceIdle(req.reason)
                    cb.addLog("INFO [Recovery] enterDeviceIdle ok=$ok cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}")
                }

                is Request.ResetConnections -> {
                    cb.resetConnectionsOptimal(req.reason, req.skipDebounce)
                    cb.addLog("INFO [Recovery] resetConnections(skipDebounce=${req.skipDebounce}) cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}")
                }

                is Request.ResetCoreNetwork -> {
                    cb.resetCoreNetwork(req.reason, req.force)
                    cb.addLog("INFO [Recovery] resetCoreNetwork(force=${req.force}) cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}")
                }

                is Request.Restart -> {
                    cb.restartVpnService(req.reason)
                    cb.addLog("INFO [Recovery] restart cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}")
                }

                else -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recovery request failed: ${req.javaClass.simpleName} reason=${req.reason}", e)
            cb.addLog("WARN [Recovery] ${req.javaClass.simpleName} failed: ${e.message} reason=${req.reason}")
        }
    }

    sealed interface Request {
        val priority: Int
        val reason: String
        val requestedAtMs: Long

        fun withReason(reason: String): Request

        data class Recover(
            val mode: Int,
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = when (mode) {
                3 -> 90 // deep
                2 -> 80 // full
                4 -> 70 // proactive
                1 -> 60 // quick
                else -> 55 // auto
            }

            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class ResetConnections(
            override val reason: String,
            val skipDebounce: Boolean,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = 40
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class ResetCoreNetwork(
            override val reason: String,
            val force: Boolean,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = if (force) 50 else 45
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class Restart(
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = 100
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class EnterDeviceIdle(
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            // High priority: entering doze should override most resets/recoveries.
            override val priority: Int = 95
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class Composite(
            val items: List<Request>,
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = items.maxOf { it.priority }
            override fun withReason(reason: String): Request = copy(reason = reason)
        }
    }
}
