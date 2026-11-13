package io.cabincaption.protocol

/** HELLO·CTRL 본문 = 문자열 값만 가진 평면 JSON 객체(UTF-8). 의존성 없이 직접 인코딩·검증한다. */
object Ctrl {
    fun encode(fields: Map<String, String>): ByteArray =
        fields.entries.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + quote(v) }.toByteArray(Charsets.UTF_8)

    fun decode(body: ByteArray): Map<String, String> = Parser(String(body, Charsets.UTF_8)).obj()

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        private fun fail(why: String): Nothing = throw ProtocolException("bad ctrl at $i: $why")

        private fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        private fun expect(c: Char) {
            ws()
            if (i >= s.length || s[i] != c) fail("expected '$c'")
            i++
        }

        fun obj(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            expect('{')
            ws()
            if (i < s.length && s[i] == '}') {
                i++
            } else {
                while (true) {
                    val k = str()
                    expect(':')
                    val v = str()
                    if (out.put(k, v) != null) fail("duplicate key")
                    ws()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    expect('}')
                    break
                }
            }
            ws()
            if (i != s.length) fail("trailing data")
            return out
        }

        private fun str(): String {
            ws()
            if (i >= s.length || s[i] != '"') fail("expected string")
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> b.append('"')
                            '\\' -> b.append('\\')
                            '/' -> b.append('/')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad unicode escape")
                                b.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape '$e'")
                        }
                    }
                    c < ' ' -> fail("control char in string")
                    else -> b.append(c)
                }
            }
        }
    }
}
