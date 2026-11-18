package com.goob.ddhelper

/**
 * Edits values in a Wine registry hive (user.reg / system.reg) as text. It only changes values that
 * already exist and keeps their type (string or dword), so it never invents registry state the game
 * did not write itself.
 *
 *   [Software\\IO Interactive\\HITMAN3] 1700000000
 *   "SuperSampling"="1000"
 *   "SharpeningStrength"=dword:00000002
 */
object WineReg {
    private fun header(keyPath: String) = "[" + keyPath.replace("\\", "\\\\") + "]"

    private fun section(lines: List<String>, keyPath: String): IntRange? {
        val h = header(keyPath)
        val start = lines.indexOfFirst { it.startsWith(h, ignoreCase = true) }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size && !lines[end].startsWith("[")) end++
        return (start + 1) until end
    }

    private fun valueLine(lines: List<String>, r: IntRange, name: String): Int? {
        val prefix = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"="
        return r.firstOrNull { lines[it].startsWith(prefix, ignoreCase = true) }
    }

    /** The raw right-hand side of a value, for example "\"1000\"" or "dword:00000002". */
    fun getRaw(text: String, keyPath: String, name: String): String? {
        val lines = text.split("\n")
        val r = section(lines, keyPath) ?: return null
        val i = valueLine(lines, r, name) ?: return null
        return lines[i].substringAfter("=").trimEnd('\r')
    }

    /** Replaces an existing value's raw right-hand side. Returns null when the value is not there. */
    fun setRaw(text: String, keyPath: String, name: String, rhs: String): String? {
        val lines = text.split("\n").toMutableList()
        val r = section(lines, keyPath) ?: return null
        val i = valueLine(lines, r, name) ?: return null
        val cr = if (lines[i].endsWith("\r")) "\r" else ""
        lines[i] = lines[i].substringBefore("=") + "=" + rhs + cr
        return lines.joinToString("\n")
    }

    /** A number encoded with the same type as [oldRhs], or null for types this helper won't touch. */
    fun numberLike(oldRhs: String, n: Long): String? = when {
        oldRhs.startsWith("dword:", ignoreCase = true) -> "dword:%08x".format(n)
        oldRhs.startsWith("\"") -> "\"$n\""
        else -> null
    }
}
