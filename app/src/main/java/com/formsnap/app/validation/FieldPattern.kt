package com.formsnap.app.validation

/** A bounded, non-backtracking regex subset for field formats: literals, classes, \d/\w/\s,
 * . and ?, *, +, {n}, {n,m}. Groups, alternatives and lookarounds are intentionally unsupported.
 */
class FieldPattern(pattern: String) {
    private data class Token(val character: Regex, val min: Int, val max: Int)
    private val tokens: List<Token>

    init {
        require(pattern.length <= 240)
        val input = pattern.removePrefix("^").removeSuffix("$")
        val parsed = mutableListOf<Token>()
        var offset = 0
        while (offset < input.length) {
            val start = offset
            when (val ch = input[offset++]) {
                '\\' -> { require(offset < input.length); require(input[offset] in "dDwWsS\\.-+?*[]{}^$"); offset++ }
                '[' -> {
                    var escaped = false
                    while (offset < input.length) {
                        val next = input[offset++]
                        if (next == ']' && !escaped) break
                        escaped = next == '\\' && !escaped
                    }
                    require(input[offset - 1] == ']')
                }
                else -> require(ch !in "()|{}?*+^$")
            }
            val character = Regex(input.substring(start, offset))
            var min = 1
            var max = 1
            if (offset < input.length) when (input[offset]) {
                '?' -> { min = 0; max = 1; offset++ }
                '*' -> { min = 0; max = MAX_VALUE_LENGTH; offset++ }
                '+' -> { min = 1; max = MAX_VALUE_LENGTH; offset++ }
                '{' -> {
                    val end = input.indexOf('}', offset)
                    require(end > offset)
                    val bounds = input.substring(offset + 1, end).split(',')
                    require(bounds.size in 1..2)
                    min = bounds[0].toInt()
                    max = if (bounds.size == 1) min else bounds[1].takeIf { it.isNotEmpty() }?.toInt() ?: MAX_VALUE_LENGTH
                    require(min in 0..MAX_VALUE_LENGTH && max in min..MAX_VALUE_LENGTH)
                    offset = end + 1
                }
            }
            parsed += Token(character, min, max)
            require(parsed.size <= 64)
        }
        tokens = parsed
    }

    fun matches(value: String): Boolean {
        if (value.length > MAX_VALUE_LENGTH) return false
        var reachable = booleanArrayOf(true) + BooleanArray(value.length)
        for (token in tokens) {
            val next = BooleanArray(value.length + 1)
            for (start in reachable.indices) if (reachable[start]) {
                if (token.min == 0) next[start] = true
                var end = start
                while (end < value.length && end - start < token.max && token.character.matches(value[end].toString())) {
                    end++
                    if (end - start >= token.min) next[end] = true
                }
            }
            reachable = next
        }
        return reachable[value.length]
    }

    companion object { const val MAX_VALUE_LENGTH = 256 }
}
