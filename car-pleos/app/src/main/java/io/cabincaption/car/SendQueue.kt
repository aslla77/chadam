package io.cabincaption.car

import io.cabincaption.protocol.Frame
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 폰 한 대로 보낼 프레임 줄(ADR-0004 결정 5). 넣기는 막지 않고, 보내는 스레드 하나가 [take]로 꺼내 쓴다.
 * - 오디오(CAR_PCM)는 [maxAudio]개(= 100ms × n)까지만 둔다. 넘으면 가장 오래된 오디오부터 버린다.
 * - 제어 프레임은 버리지 않는다. 대신 [maxControl]개가 쌓이면 그 폰은 멈춘 것으로 보고 [Offer.FULL]을 돌려준다(호출한 쪽이 연결을 닫는다).
 * - 순서는 넣은 순서 그대로다.
 */
class SendQueue(private val maxAudio: Int = MAX_AUDIO, private val maxControl: Int = MAX_CONTROL) {
    enum class Offer { ACCEPTED, CLOSED, FULL }

    private class Item(val frame: Frame, val audio: Boolean)

    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val items = ArrayDeque<Item>()
    private var audio = 0
    private var closed = false
    private var finishing = false
    private var dropped = 0L

    val droppedAudio: Long get() = lock.withLock { dropped }

    /** 제어 프레임은 버리지 않는다. [Offer.FULL]이면 그 폰이 받지 못하고 있다는 뜻이다. */
    fun offerControl(f: Frame): Offer = lock.withLock {
        if (closed || finishing) return Offer.CLOSED
        if (items.size - audio >= maxControl) return Offer.FULL
        items.addLast(Item(f, audio = false))
        ready.signalAll()
        Offer.ACCEPTED
    }

    /** 넣고 넘친 만큼 오래된 오디오를 버린다. 닫혔으면 아무것도 하지 않는다. */
    fun offerAudio(f: Frame): Unit = lock.withLock {
        if (closed || finishing) return
        items.addLast(Item(f, audio = true))
        audio++
        if (audio > maxAudio) {
            val it = items.iterator()
            while (audio > maxAudio && it.hasNext()) {
                if (it.next().audio) {
                    it.remove()
                    audio--
                    dropped++
                }
            }
        }
        ready.signalAll()
    }

    /** 다음 프레임. 비어 있으면 기다린다. 닫혔거나 [finish] 뒤 다 꺼냈으면 null. */
    fun take(): Frame? = lock.withLock {
        while (items.isEmpty() && !closed && !finishing) ready.await()
        if (closed) return null
        val item = items.pollFirst() ?: return null
        if (item.audio) audio--
        item.frame
    }

    /** 남은 오디오는 버리고 [last]를 마지막으로 넣는다. 그 뒤로는 아무것도 받지 않는다. */
    fun finish(last: Frame): Unit = lock.withLock {
        if (closed || finishing) return
        val it = items.iterator()
        while (it.hasNext()) {
            if (it.next().audio) {
                it.remove()
                dropped++
            }
        }
        audio = 0
        items.addLast(Item(last, audio = false))
        finishing = true
        ready.signalAll()
    }

    fun close(): Unit = lock.withLock {
        closed = true
        items.clear()
        audio = 0
        ready.signalAll()
    }

    companion object {
        /** 1초 = 100ms 청크 10개(ADR-0004 결정 5의 "1초"). */
        const val MAX_AUDIO = 10
        const val MAX_CONTROL = 64
    }
}
