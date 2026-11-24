package app.dearnotes.editor

import app.dearnotes.isJavaBlank
import app.dearnotes.isJavaWhitespace

import app.dearnotes.model.Mark

/**
 * marks 列の JSON: [[start,end,"style"],…]。ページへのリンクだけ4つ目にページの id を持つ。
 * 形が決まっているので手で読む。古い版は要素が3つでない装飾を捨てる。
 */
object MarksCodec {
    private const val HEX = "0123456789abcdef"

    fun encode(marks: List<Mark>?): String? {
        if (marks.isNullOrEmpty()) return null
        val sb = StringBuilder("[")
        for (i in marks.indices) {
            val m = marks[i]
            if (i > 0) sb.append(',')
            sb.append('[').append(m.start).append(',').append(m.end).append(',')
            appendJsonString(sb, m.style.key())
            if (m.target != null) {
                sb.append(',')
                appendJsonString(sb, m.target)
            }
            sb.append(']')
        }
        return sb.append(']').toString()
    }

    fun decode(json: String?): List<Mark> {
        if (json == null || json.isJavaBlank()) return emptyList()
        return Decoder(json).decode()
    }

    /** RFC 8259 に従い JSON 文字列として書き出す。 */
    private fun appendJsonString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u00").append(HEX[c.code ushr 4]).append(HEX[c.code and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /** 引用符内のカンマや括弧・エスケープを考慮して配列の配列を読む。 */
    private class Decoder(private val s: String) {
        private var pos = 0

        fun decode(): List<Mark> {
            skipWs()
            if (pos >= s.length || s[pos] != '[') return emptyList()
            pos++
            val out = ArrayList<Mark>()
            while (pos < s.length) {
                skipWs()
                if (pos >= s.length || s[pos] == ']') break
                when (s[pos]) {
                    ',' -> pos++
                    '[' -> readMark()?.let { out.add(it) }
                    else -> skipValue()
                }
            }
            return out.toList()
        }

        /** 1件の装飾配列 `[start, end, "style"(, "target")]` を読む。壊れていれば閉じ `]` まで飛ばす。 */
        private fun readMark(): Mark? {
            pos++ // '[' を消費
            val start = readElementInt() ?: return skipToArrayEnd()
            if (!expectComma()) return skipToArrayEnd()
            val end = readElementInt() ?: return skipToArrayEnd()
            if (!expectComma()) return skipToArrayEnd()
            val style = readElementString() ?: return skipToArrayEnd()

            skipWs()
            var target: String? = null
            if (pos < s.length && s[pos] == ',') {
                pos++
                skipWs()
                // 末尾カンマ（例: [0,3,"bold",]）は従来どおり許容する
                if (pos < s.length && s[pos] != ']') {
                    target = readElementString() ?: return skipToArrayEnd()
                    skipWs()
                    if (pos < s.length && s[pos] == ',') {
                        pos++
                        skipWs()
                    }
                }
            }
            if (pos >= s.length || s[pos] != ']') return skipToArrayEnd()
            pos++ // ']' を消費
            return try {
                Mark(start, end, Mark.Style.fromKey(style), target)
            } catch (_: IllegalArgumentException) {
                // 知らない装飾や壊れた値は捨てる
                null
            }
        }

        private fun expectComma(): Boolean {
            skipWs()
            if (pos < s.length && s[pos] == ',') {
                pos++
                return true
            }
            return false
        }

        private fun readElementInt(): Int? {
            skipWs()
            val from = pos
            if (pos < s.length && (s[pos] == '-' || s[pos] == '+')) pos++
            val digitsStart = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == digitsStart) return null
            return s.substring(from, pos).toIntOrNull()
        }

        private fun readElementString(): String? {
            skipWs()
            if (pos >= s.length || s[pos] != '"') return null
            pos++ // '"' を消費
            val sb = StringBuilder()
            var valid = true
            while (pos < s.length) {
                val c = s[pos++]
                if (c == '"') return if (valid) sb.toString() else null
                if (c == '\\') {
                    if (pos >= s.length) return null
                    when (val esc = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val code = if (pos + 4 <= s.length) parseHex4(pos) else null
                            if (code != null) {
                                pos += 4
                                sb.append(code.toChar())
                            } else {
                                valid = false
                            }
                        }
                        else -> valid = false
                    }
                } else {
                    sb.append(c)
                }
            }
            return null
        }

        private fun parseHex4(offset: Int): Int? {
            var value = 0
            for (k in 0 until 4) {
                val digit = when (val ch = s[offset + k]) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> return null
                }
                value = (value shl 4) or digit
            }
            return value
        }

        /** 壊れた装飾配列の対応する `]` まで（文字列と入れ子括弧を避けながら）読み飛ばす。 */
        private fun skipToArrayEnd(): Mark? {
            var depth = 1
            while (pos < s.length && depth > 0) {
                when (s[pos]) {
                    '"' -> skipString()
                    '[', '{' -> {
                        depth++
                        pos++
                    }
                    ']', '}' -> {
                        depth--
                        pos++
                    }
                    else -> pos++
                }
            }
            return null
        }

        /** 外側の配列内に現れた予期しない JSON 値を1つ読み飛ばす。 */
        private fun skipValue() {
            when (s[pos]) {
                '"' -> skipString()
                '{' -> {
                    pos++
                    skipToArrayEnd()
                }
                else -> pos++
            }
        }

        private fun skipString() {
            pos++ // 開始の '"'
            while (pos < s.length) {
                val c = s[pos++]
                if (c == '\\') {
                    if (pos < s.length) pos++
                } else if (c == '"') {
                    return
                }
            }
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isJavaWhitespace()) pos++
        }
    }
}
