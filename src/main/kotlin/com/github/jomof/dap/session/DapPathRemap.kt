package com.github.jomof.dap.session

import java.io.File

/**
 * Bidirectional prefix-based path remapping rules. Applied to paths the
 * adapter returns (so they resolve under the developer's source tree) and
 * inversely when constructing DAP requests (so breakpoints set on a local
 * file end up on the adapter's view of the world).
 *
 * Order matters: the first matching rule wins.
 *
 * Each rule rewrites a leading path prefix; everything after the matched
 * prefix is preserved verbatim. Comparisons are case-sensitive on POSIX
 * filesystems and case-insensitive on Windows.
 */
data class DapPathRemap(
    val rules: List<Rule> = emptyList(),
) {
    data class Rule(val from: String, val to: String)

    fun toLocal(adapterPath: String): String = rewrite(adapterPath, fromAdapterRules)

    fun toAdapter(localPath: String): String = rewrite(localPath, toAdapterRules)

    private val fromAdapterRules: List<Rule> = rules
    private val toAdapterRules: List<Rule> = rules.map { Rule(it.to, it.from) }

    private fun rewrite(input: String, rules: List<Rule>): String {
        if (rules.isEmpty()) return input
        val normalised = normalise(input)
        for (rule in rules) {
            val from = normalise(rule.from)
            if (matchesPrefix(normalised, from)) {
                val tail = input.substring(rule.from.length)
                return rule.to + tail
            }
        }
        return input
    }

    private fun normalise(path: String): String {
        val withForwardSlashes = path.replace(File.separatorChar, '/')
        return if (isWindows) withForwardSlashes.lowercase() else withForwardSlashes
    }

    private fun matchesPrefix(haystack: String, needle: String): Boolean {
        if (!haystack.startsWith(needle)) return false
        if (haystack.length == needle.length) return true
        if (needle.endsWith('/')) return true
        return haystack[needle.length] == '/'
    }

    companion object {
        val EMPTY: DapPathRemap = DapPathRemap(emptyList())
        private val isWindows: Boolean = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true
    }
}
