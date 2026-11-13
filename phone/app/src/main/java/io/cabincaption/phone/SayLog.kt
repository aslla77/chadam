package io.cabincaption.phone

import io.cabincaption.protocol.SayState
import io.cabincaption.protocol.Wire

/** 이 폰이 보낸 문장과 상태. 메모리에만 두고 나가기·차 꺼짐·연결 잃음 때 지운다. */
class SayLog {
    enum class Step(val rank: Int) {
        MAKING(0),
        SENT(1),
        QUEUED(2),

        /** 내비 안내가 끝나기를 기다림. 재생 중에 내비가 끼면 PLAYING에서 다시 올 수 있다(같은 단계). */
        WAITING(3),
        PLAYING(3),
        DONE(4),
        FAILED(4),
    }

    /** [CALL] = 통화 중이라 차가 읽지 않음, [NAV] = 내비 안내가 길어 차가 읽지 못함, [NOBODY] = 소리 없이 보낸 글을 받을 폰이 없음. */
    enum class Failure { TOO_LONG, SYNTH, NOT_CONNECTED, CAR, CALL, NAV, LOST, NOBODY }

    /** [silent] = 차 스피커를 끄고 다른 폰에만 보낸 글(note). */
    data class Item(val id: String, val text: String, val step: Step, val failure: Failure? = null, val silent: Boolean = false)

    private val items = ArrayList<Item>()

    @Synchronized
    fun add(id: String, text: String, silent: Boolean = false) {
        items += Item(id, text, Step.MAKING, silent = silent)
    }

    @Synchronized
    fun sent(id: String) = move(id, Step.SENT, null)

    @Synchronized
    fun failed(id: String, why: Failure) = move(id, Step.FAILED, why)

    /** 차가 보낸 상태와 이유. 순서가 뒤바뀌어 와도(QUEUED가 PLAYING 뒤) 상태를 되돌리지 않는다. 모르는 실패 이유는 [Failure.CAR]. */
    @Synchronized
    fun car(id: String, state: SayState, reason: String? = null) = when (state) {
        SayState.QUEUED -> move(id, Step.QUEUED, null)
        SayState.WAITING -> move(id, Step.WAITING, null)
        SayState.PLAYING -> move(id, Step.PLAYING, null)
        SayState.DONE -> move(id, Step.DONE, null)
        SayState.FAILED -> move(
            id,
            Step.FAILED,
            when (reason) {
                Wire.SAID_CALL -> Failure.CALL
                Wire.SAID_NAV -> Failure.NAV
                Wire.SAID_NOBODY -> Failure.NOBODY
                else -> Failure.CAR
            },
        )
    }

    /** 연결이 끊기면 차에 이미 보낸 문장은 결과를 알 수 없어 실패(LOST)로 둔다. 만드는 중인 문장은 보낼 때 실패한다. */
    @Synchronized
    fun disconnected() {
        for (i in items.indices) {
            val it = items[i]
            if (it.step != Step.MAKING && it.step.rank < Step.DONE.rank) items[i] = it.copy(step = Step.FAILED, failure = Failure.LOST)
        }
    }

    @Synchronized
    fun clear() = items.clear()

    @Synchronized
    fun snapshot(): List<Item> = items.toList()

    private fun move(id: String, to: Step, why: Failure?) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = items[i]
        if (cur.step.rank >= Step.DONE.rank || to.rank < cur.step.rank || to == cur.step) return
        items[i] = cur.copy(step = to, failure = why)
    }

    companion object {
        const val MAX_CHARS = Wire.SAY_TEXT_MAX_CHARS
    }
}
