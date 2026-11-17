package com.wusper.auto.fill

/**
 * Extracts a 4–8 digit OTP from notification title/text.
 * Prefers a code next to an OTP phrase over years, dates, or phone tails.
 * Display must never use the full message body — this returns the code only.
 */
object OtpParser {

    private val KEYWORD_RE = Regex(
        """(?:驗證碼|验证码|校驗碼|校验码|認證碼|认证码|確認碼|确认码|動態密碼|动态密码|動態碼|动态码|一次[性性](?:密碼|密码|驗證碼|验证码)|簡訊驗證碼|短信验证码|短信碼|簡訊碼|郵件驗證碼|邮箱验证码|安全碼|安全码|校驗代碼|校验代码|驗證密碼|验证密码|verification codes?|auth(?:entication)? codes?|security codes?|one[-\s]?time(?:\s+pass(?:word|code)?|\s+codes?)?|\bOTP\b|\b2FA\b|passcodes?|login codes?|sign[-\s]?in codes?|confirmation codes?|verify codes?|your codes?|(?:the\s+)?codes? is)""",
        RegexOption.IGNORE_CASE
    )

    private val TAIL_RE = Regex(
        """(?:尾號|尾号|ending in|ends in|ended in|ends with|ending with|last\s*(?:four|4)\s*digits?)""",
        RegexOption.IGNORE_CASE
    )

    private val CODE_RE = Regex("""(?<![A-Za-z0-9])(?:[A-Z]-)?(\d{4,8})(?![A-Za-z0-9])""")
    private val GROUPED_RE = Regex("""(?<![A-Za-z0-9])((?:\d{2,4}[\s-]){1,2}\d{2,4})(?![A-Za-z0-9-])""")
    private val YEAR_RE = Regex("""^(?:19|20)\d{2}$""")
    private val DATE8_RE = Regex("""^(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])$""")

    data class Result(val code: String)

    fun extract(title: CharSequence?, text: CharSequence?, bigText: CharSequence? = null): String? {
        val primary = join(title, text)
        extractFrom(primary, allowIsolated = true)?.let { return it }
        if (!bigText.isNullOrBlank()) {
            return extractFrom(join(title, text, bigText), allowIsolated = false)
        }
        return null
    }

    internal fun extractFrom(raw: String, allowIsolated: Boolean = true): String? {
        val text = normalize(raw)
        if (text.isBlank()) return null
        val candidates = findCandidates(text)
        if (candidates.isEmpty()) return null

        val contextual = candidates.filter { it.usable && it.nearKeyword }
        if (contextual.isNotEmpty()) {
            return contextual.maxBy { it.score }.code
        }

        if (!allowIsolated) return null

        val isolated = candidates.filter { it.usable && it.isolated }
        val compact = text.replace(Regex("""\s+"""), " ").trim()
        if (isolated.size == 1) {
            val only = isolated[0]
            val justCode = compact == only.code ||
                compact.equals("G-${only.code}", ignoreCase = true) ||
                compact.matches(Regex("""(?:[A-Z]-)?${Regex.escape(only.code)}[.。]?"""))
            // Isolated 4-digit tokens are too easy to confuse with PINs, years, or tails.
            if (justCode && only.code.length in 5..8) return only.code
            if (only.code.length in 5..8 && compact.length <= 40 && candidates.none { it !== only && it.usable }) {
                return only.code
            }
        }
        return null
    }

    private data class Candidate(
        val code: String,
        val start: Int,
        val end: Int,
        val nearKeyword: Boolean,
        val isolated: Boolean,
        val gPrefix: Boolean,
        val usable: Boolean,
        val score: Int
    )

    private fun findCandidates(text: String): List<Candidate> {
        val keywordRanges = KEYWORD_RE.findAll(text).map { it.range }.toList()
        val tailRanges = TAIL_RE.findAll(text).map { it.range }.toList()

        val found = linkedMapOf<Pair<Int, Int>, Pair<String, Boolean>>()

        CODE_RE.findAll(text).forEach { m ->
            val code = m.groupValues[1]
            val gPrefix = m.value.length > code.length && m.value[1] == '-'
            found[m.range.first to m.range.last] = code to gPrefix
        }
        GROUPED_RE.findAll(text).forEach { m ->
            val digits = m.groupValues[1].filter { it.isDigit() }
            if (digits.length in 4..8 && (m.range.first to m.range.last) !in found.keys) {
                // Skip groups that are clearly a phone-shaped split of a longer number already captured.
                found[m.range.first to m.range.last] = digits to false
            }
        }

        return found.map { (range, payload) ->
            val (start, endIncl) = range
            val end = endIncl + 1
            val (code, gPrefix) = payload
            val isYear = YEAR_RE.matches(code)
            val isDate = DATE8_RE.matches(code)
            val isTail = tailRanges.any { tail ->
                start >= tail.last + 1 && start <= tail.last + 9 &&
                    noKeywordBetween(keywordRanges, tail.last + 1, start)
            }
            val nearAfter = keywordRanges.any { kw ->
                start >= kw.last + 1 && start <= kw.last + 41
            }
            val nearBefore = keywordRanges.any { kw ->
                kw.first >= end && kw.first <= end + 24
            }
            val nearKeyword = nearAfter || nearBefore || gPrefix
            val usable = !isYear && !isDate && !isTail
            val isolated = usable && keywordRanges.isEmpty()
            var score = when (code.length) {
                6 -> 5
                5, 7 -> 3
                8 -> 2
                4 -> 1
                else -> 0
            }
            if (nearAfter) score += 8
            if (nearBefore) score += 6
            if (gPrefix) score += 5
            if (isYear || isDate || isTail) score = Int.MIN_VALUE
            Candidate(code, start, end, nearKeyword, isolated, gPrefix, usable, score)
        }
    }

    private fun noKeywordBetween(keywordRanges: List<IntRange>, from: Int, to: Int): Boolean {
        return keywordRanges.none { it.first >= from && it.first < to }
    }

    private fun join(vararg parts: CharSequence?): String {
        return parts.filter { !it.isNullOrBlank() }.joinToString("\n") { it.toString() }
    }

    internal fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '\u00A0', '\u3000' -> sb.append(' ')
                in '\uFF10'..'\uFF19' -> sb.append('0' + (ch - '\uFF10'))
                '：' -> sb.append(':')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
