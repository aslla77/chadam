package io.cabincaption.protocol

import java.io.IOException

enum class FrameType(val code: Int) {
    HELLO(1),
    CTRL(2),
    CAR_PCM(3),
    PHONE_PCM(4),
    ;

    companion object {
        fun of(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

class Frame(val type: FrameType, val body: ByteArray) {
    override fun equals(other: Any?): Boolean = other is Frame && other.type == type && other.body.contentEquals(body)

    override fun hashCode(): Int = 31 * type.hashCode() + body.contentHashCode()

    override fun toString(): String = "Frame($type, ${body.size}B)"
}

class ProtocolException(message: String) : IOException(message)
