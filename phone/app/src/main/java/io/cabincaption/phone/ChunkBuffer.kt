package io.cabincaption.phone

import java.util.ArrayDeque

/**
 * 인식기에 넣기 전 차 소리 청크를 잠깐 모아 두는 링버퍼(ADR-0002 재시작 8번: 최대 2초).
 * 가득 차면 가장 오래된 청크를 버린다. 메모리에만 있다.
 */
class ChunkBuffer(private val capacity: Int = CAPACITY) {
    private val q = ArrayDeque<ByteArray>()
    private val lock = Object()
    var dropped = 0L
        private set

    fun offer(pcm: ByteArray) = synchronized(lock) {
        if (q.size >= capacity) {
            q.removeFirst()
            dropped++
        }
        q.addLast(pcm)
        lock.notifyAll()
    }

    /** 청크가 올 때까지 최대 [timeoutMs] 기다린다. 없으면 null. */
    fun take(timeoutMs: Long): ByteArray? = synchronized(lock) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (q.isEmpty()) {
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            lock.wait(left)
        }
        q.removeFirst()
    }

    fun isEmpty(): Boolean = synchronized(lock) { q.isEmpty() }

    fun size(): Int = synchronized(lock) { q.size }

    fun clear() = synchronized(lock) { q.clear() }

    companion object {
        /** 20 × 100ms = 2초. */
        const val CAPACITY = 20
    }
}
