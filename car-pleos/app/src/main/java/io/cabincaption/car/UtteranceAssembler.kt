package io.cabincaption.car

import io.cabincaption.protocol.Wire
import java.io.ByteArrayOutputStream

/** [text] = 다른 폰에 보여 줄 글([Wire.validText]를 통과한 것만, 없으면 공유하지 않음). */
class Utterance(val id: String, val rate: Int, val pcm: ByteArray, val text: String? = null)

/** 한 연결에서 온 say → PHONE_PCM… → say_end를 한 문장으로 모은다. 메모리에만 두고, 길이 상한을 넘으면 버린다. */
class UtteranceAssembler {

    sealed interface Result {
        data object Pending : Result

        /** 처리할 것이 없어 버린 프레임(시작 없는 PCM, 짝 없는 끝). */
        data object Ignored : Result

        class Ready(val utterance: Utterance) : Result

        data class Failed(val id: String, val reason: String) : Result
    }

    private var id: String? = null
    private var rate = 0
    private var text: String? = null
    private var buf = ByteArrayOutputStream()

    /** 이전 문장이 끝나지 않았으면 그 문장은 실패로 알리고 새로 시작한다. 글이 맞지 않으면 글만 버린다(소리는 읽는다). */
    fun start(fields: Map<String, String>): Result {
        val prev = id
        reset()
        val newId = fields[Wire.ID]
        val newRate = fields[Wire.RATE]?.toIntOrNull()
        if (!Wire.validId(newId)) return Result.Ignored
        if (!Wire.validRate(newRate)) return Result.Failed(newId!!, REASON_BAD_FORMAT)
        id = newId
        rate = newRate!!
        text = fields[Wire.TEXT]?.takeIf(Wire::validText)
        return if (prev != null) Result.Failed(prev, REASON_INTERRUPTED) else Result.Pending
    }

    fun pcm(body: ByteArray): Result {
        val cur = id ?: return Result.Ignored
        if (buf.size() + body.size > Wire.sayMaxBytes(rate)) {
            reset()
            return Result.Failed(cur, REASON_TOO_LONG)
        }
        buf.write(body)
        return Result.Pending
    }

    fun end(fields: Map<String, String>): Result {
        val cur = id
        if (cur == null || fields[Wire.ID] != cur) return Result.Ignored
        val pcm = buf.toByteArray()
        val r = rate
        val t = text
        reset()
        return when {
            pcm.isEmpty() -> Result.Failed(cur, REASON_EMPTY)
            pcm.size % 2 != 0 -> Result.Failed(cur, REASON_BAD_FORMAT)
            else -> Result.Ready(Utterance(cur, r, pcm, t))
        }
    }

    private fun reset() {
        id = null
        rate = 0
        text = null
        buf = ByteArrayOutputStream()
    }

    companion object {
        const val REASON_BAD_FORMAT = "format"
        const val REASON_INTERRUPTED = "interrupted"
        const val REASON_TOO_LONG = "too_long"
        const val REASON_EMPTY = "empty"
    }
}
