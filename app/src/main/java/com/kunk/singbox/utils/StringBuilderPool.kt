package com.kunk.singbox.utils

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * StringBuilder 对象池，减少高频字符串操作的 GC 压力
 */
object StringBuilderPool {
    private const val MAX_POOL_SIZE = 16
    private const val DEFAULT_CAPACITY = 256

    private val pool = ConcurrentLinkedQueue<StringBuilder>()

    fun acquire(): StringBuilder {
        return pool.poll()?.also { it.setLength(0) }
            ?: StringBuilder(DEFAULT_CAPACITY)
    }

    fun release(sb: StringBuilder) {
        if (pool.size < MAX_POOL_SIZE && sb.capacity() <= 4096) {
            sb.setLength(0)
            pool.offer(sb)
        }
    }

    inline fun <T> use(block: (StringBuilder) -> T): T {
        val sb = acquire()
        return try {
            block(sb)
        } finally {
            release(sb)
        }
    }
}
