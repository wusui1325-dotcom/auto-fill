package com.wusper.auto.fill

/**
 * Extracts a 4–8 character OTP from notification text.
 * Prefers a token next to an OTP phrase over years, dates, phone tails,
 * order numbers, amounts, and tracking numbers.
 * Display must never use the full message body — this returns the code only.
 */
object OtpParser {

    private val KEYWORD_RE = Regex(
        """(?:驗證碼|验证码|校驗碼|校验码|認證碼|认证码|確認碼|确认码|動態密碼|动态密码|動態碼|动态码|動態口令|动态口令|一次[性性]?(?:密碼|密码|驗證碼|验证码|驗證|验证|口令)?|簡訊驗證碼|短信验证码|短信驗證碼|簡訊验证码|短信碼|簡訊碼|短信码|简讯码|短信口令|郵件驗證碼|邮件验证码|郵箱驗證碼|邮箱验证码|安全碼|安全码|校驗代碼|校验代码|驗證代碼|验证代码|驗證密碼|验证密码|校驗密碼|校验密码|手機驗證碼|手机验证码|臨時密碼|临时密码|臨時驗證碼|临时验证码|verification codes?|auth(?:entication)? codes?|security codes?|secret codes?|access codes?|one[-\s]?time(?:\s+pass(?:word|code)?|\s+codes?)?|\bOTP\b|\b2FA\b|passcodes?|(?:login|sign[-\s]?in|confirmation|verify|sms|email|pin)\s*codes?|(?:enter|use|here's|here is)\s+(?:the\s+)?codes?|your codes?|(?:the\s+)?codes?\s*(?:is|was)|is your.{0,24}codes?|codes?[:：])""",
        RegexOption.IGNORE_CASE
    )

    private val FALSE_KEYWORD_PREFIX_RE = Regex(
        """(?:zip|postal|area|country|error|status|promo|coupon|discount|product|bar|qr|html|http|hash|short|color|colour|sort|post(?:al)?)\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val TAIL_RE = Regex(
        """(?:尾號|尾号|ending in|ends in|ended in|ends with|ending with|last\s*(?:four|4)\s*digits?)""",
        RegexOption.IGNORE_CASE
    )

    private val REJECT_CTX_RE = Regex(
        """(?:金額|金额|價錢|价格|價款|价款|合計|合计|總額|总额|實付|实付|運費|运费|訂單號|订单号|訂單編[號号]|订单编[号號]|單號|单号|運單|运单|快遞單|快递单|追蹤[號号]|追踪号|跟踪号|物流[單单號号]|tracking(?:\s*(?:number|no|id|#))?|order\s*(?:id|no|number|#)|invoice|(?:zip|postal|area|error|status|promo|coupon)\s*codes?|日期|年月日|\bdate\b)""",
        RegexOption.IGNORE_CASE
    )

    private val AMOUNT_PREFIX_RE = Regex("""(?:¥|￥|\$|€|£|USD|CNY|HKD|RMB|NT\$)\s*$""")

    private val CODE_RE = Regex("""(?<![A-Za-z0-9])(?:[A-Za-z]-)?(\d{4,8})(?![A-Za-z0-9])""")
    private val GROUPED_RE = Regex("""(?<![A-Za-z0-9])((?:\d{2,4}[\s\-–—−]){1,3}\d{2,4})(?![A-Za-z0-9\-–—−])""")
    private val ALNUM_RE = Regex("""(?<![A-Za-z0-9])([A-Za-z0-9]{4,8})(?![A-Za-z0-9])""")
    private val YEAR_RE = Regex("""^(?:19|20)\d{2}$""")
    private val DATE8_RE = Regex("""^(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])$""")
    private val DATE6_RE = Regex("""^(?:[0-9]{2})(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])$""")
    private val WHITESPACE_RE = Regex("""\s+""")

    private const val MAX_PARSE_CHARS = 4000

    data class Result(val code: String, val contextual: Boolean)

    fun extract(title: CharSequence?, text: CharSequence?, bigText: CharSequence? = null): String? {
        return extract(NotificationParts(title = title, text = text, extraChunks = listOfNotNull(bigText)))
    }

    fun extract(parts: NotificationParts): String? {
        val primary = cap(join(parts.title, parts.text))
        val full = cap(parts.combined())
        val primaryHit = extractFrom(primary, allowIsolated = parts.allowsIsolated())
        if (primaryHit != null && primaryHit.contextual) return primaryHit.code
        if (full != primary) {
            val fullHit = extractFrom(full, allowIsolated = false)
            if (fullHit != null && fullHit.contextual) return fullHit.code
        }
        return primaryHit?.code
    }

    internal fun extractFrom(raw: String, allowIsolated: Boolean = true): Result? {
        val text = normalize(raw)
        if (text.isBlank()) return null
        val candidates = findCandidates(text)
        if (candidates.isEmpty()) return null

        val contextual = candidates.filter { it.usable && it.nearKeyword }
        if (contextual.isNotEmpty()) {
            val best = contextual.maxBy { it.score }
            return Result(best.code, contextual = true)
        }

        if (!allowIsolated) return null

        val isolated = candidates.filter { it.usable && it.isolated && !it.alphanumeric }
        if (isolated.size == 1) {
            val only = isolated[0]
            if (only.code.length !in 5..8) return null
            if (DATE6_RE.matches(only.code) && only.code.length == 6) return null
            val compact = WHITESPACE_RE.replace(text, " ").trim()
            val leftover = compact
                .replace(only.code, "", ignoreCase = true)
                .replace(Regex("""(?i)(?:[A-Za-z]-)"""), "")
                .replace(Regex("""[\s:：.。,，()【】\[\]\-–—−]"""), "")
            // Isolated guesses are only for notifications that are essentially just the code.
            if (leftover.isEmpty() && only.code.length in 5..8) {
                return Result(only.code, contextual = false)
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
        val alphanumeric: Boolean,
        val usable: Boolean,
        val score: Int
    )

    private fun findCandidates(text: String): List<Candidate> {
        val keywordRanges = meaningfulKeywords(text)
        val tailRanges = TAIL_RE.findAll(text).map { it.range }.toList()
        val rejectRanges = REJECT_CTX_RE.findAll(text).map { it.range }.toList()

        val found = linkedMapOf<IntRange, Token>()

        GROUPED_RE.findAll(text).forEach { m ->
            val digits = m.groupValues[1].filter { it.isDigit() }
            if (digits.length in 4..8) {
                found[m.range] = Token(digits, gPrefix = false, alphanumeric = false)
            }
        }
        CODE_RE.findAll(text).forEach { m ->
            val code = m.groupValues[1]
            if (found.keys.any { it.first <= m.range.first && it.last >= m.range.last }) return@forEach
            val gPrefix = m.value.length > code.length && m.value.contains('-')
            found[m.range] = Token(code, gPrefix, alphanumeric = false)
        }
        if (keywordRanges.isNotEmpty()) {
            ALNUM_RE.findAll(text).forEach { m ->
                val token = m.groupValues[1]
                if (isAlphanumericCode(token) && found.keys.none { it.first == m.range.first }) {
                    found[m.range] = Token(token, gPrefix = false, alphanumeric = true)
                }
            }
        }

        return found.map { (range, token) ->
            val start = range.first
            val end = range.last + 1
            val code = token.code
            val isYear = !token.alphanumeric && YEAR_RE.matches(code)
            val isDate = !token.alphanumeric && (DATE8_RE.matches(code) || (code.length == 6 && DATE6_RE.matches(code) && keywordRanges.isEmpty()))
            val isTail = tailRanges.any { tail ->
                start >= tail.last + 1 && start <= tail.last + 9 &&
                    noRangeBetween(keywordRanges, tail.last + 1, start)
            }
            val isAmount = AMOUNT_PREFIX_RE.containsMatchIn(text.substring(0, start).takeLast(8))
            val nearReject = rejectRanges.any { ctx ->
                distance(ctx, start, end) <= 20 &&
                    keywordRanges.none { kw -> distance(kw, start, end) < distance(ctx, start, end) }
            }
            val nearAfter = keywordRanges.any { kw -> start >= kw.last + 1 && start <= kw.last + 48 }
            val nearBefore = keywordRanges.any { kw -> kw.first >= end && kw.first <= end + 28 }
            val nearKeyword = nearAfter || nearBefore || token.gPrefix
            val usable = !isYear && !isDate && !isTail && !isAmount && !nearReject &&
                (!token.alphanumeric || nearKeyword)
            val isolated = usable && keywordRanges.isEmpty() && !token.alphanumeric
            var score = when {
                token.alphanumeric && code.length == 6 -> 4
                token.alphanumeric -> 2
                code.length == 6 -> 5
                code.length == 5 || code.length == 7 -> 3
                code.length == 8 -> 2
                code.length == 4 -> 1
                else -> 0
            }
            if (nearAfter) score += 8
            if (nearBefore) score += 6
            if (token.gPrefix) score += 5
            if (isYear || isDate || isTail || isAmount || nearReject) score = Int.MIN_VALUE
            Candidate(
                code = code,
                start = start,
                end = end,
                nearKeyword = nearKeyword,
                isolated = isolated,
                gPrefix = token.gPrefix,
                alphanumeric = token.alphanumeric,
                usable = usable,
                score = score
            )
        }
    }

    private data class Token(val code: String, val gPrefix: Boolean, val alphanumeric: Boolean)

    private fun meaningfulKeywords(text: String): List<IntRange> {
        return KEYWORD_RE.findAll(text).mapNotNull { match ->
            val prefix = text.substring(0, match.range.first).takeLast(16)
            if (FALSE_KEYWORD_PREFIX_RE.containsMatchIn(prefix)) null else match.range
        }.toList()
    }

    private fun isAlphanumericCode(token: String): Boolean {
        if (token.length !in 4..8) return false
        val letters = token.any { it.isLetter() }
        val digits = token.any { it.isDigit() }
        return letters && digits
    }

    private fun noRangeBetween(ranges: List<IntRange>, from: Int, to: Int): Boolean {
        return ranges.none { it.first >= from && it.first < to }
    }

    private fun distance(range: IntRange, start: Int, end: Int): Int {
        return if (range.last < start) start - range.last - 1
        else if (range.first >= end) range.first - end
        else 0
    }

    private fun join(vararg parts: CharSequence?): String {
        return parts.filter { !it.isNullOrBlank() }.joinToString("\n") { it.toString() }
    }

    private fun cap(text: String): String {
        if (text.length <= MAX_PARSE_CHARS) return text
        return text.substring(0, 800) + "\n" + text.substring(text.length - (MAX_PARSE_CHARS - 800))
    }

    internal fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '\u00A0', '\u3000' -> sb.append(' ')
                in '\uFF10'..'\uFF19' -> sb.append('0' + (ch - '\uFF10'))
                in '\uFF21'..'\uFF3A' -> sb.append('A' + (ch - '\uFF21'))
                in '\uFF41'..'\uFF5A' -> sb.append('a' + (ch - '\uFF41'))
                '：' -> sb.append(':')
                '－', '—', '–', '−' -> sb.append('-')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

/** Gathered notification strings. Never shown in the confirm UI except via SourceLabel. */
data class NotificationParts(
    val title: CharSequence? = null,
    val text: CharSequence? = null,
    val extraChunks: List<CharSequence> = emptyList(),
    val messages: List<CharSequence> = emptyList()
) {
    fun combined(): String {
        val out = StringBuilder()
        fun append(value: CharSequence?) {
            if (value.isNullOrBlank()) return
            if (out.isNotEmpty()) out.append('\n')
            out.append(value)
        }
        append(title)
        append(text)
        extraChunks.forEach { append(it) }
        // Newest messages last so a nearby keyword still binds to the latest code.
        messages.forEach { append(it) }
        return out.toString()
    }

    fun allowsIsolated(): Boolean {
        val primaryLen = (title?.length ?: 0) + (text?.length ?: 0)
        val rest = extraChunks.sumOf { it.length } + messages.sumOf { it.length }
        return primaryLen <= 80 && rest <= 160
    }
}
