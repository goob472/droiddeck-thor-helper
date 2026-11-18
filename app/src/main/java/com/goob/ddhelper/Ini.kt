package com.goob.ddhelper

/** Line-based editor for .ini files (Unreal, MT Framework, Forge). Keeps every untouched line as it was. */
object Ini {
    private fun isHeader(line: String) = line.trim().let { it.startsWith("[") && it.endsWith("]") }
    private fun headerName(line: String) = line.trim().removePrefix("[").removeSuffix("]")
    private fun keyOf(line: String): String? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith(";") || t.startsWith("#") || isHeader(t)) return null
        val eq = t.indexOf('=')
        return if (eq > 0) t.substring(0, eq).trim() else null
    }

    /** Index range [start, end) of the lines in [section] (excluding the header), or null. */
    private fun range(lines: List<String>, section: String): IntRange? {
        val h = lines.indexOfFirst { isHeader(it) && headerName(it).equals(section, true) }
        if (h < 0) return null
        var end = h + 1
        while (end < lines.size && !isHeader(lines[end])) end++
        return (h + 1) until end
    }

    /** Replaces the value on an existing `key=value` line, keeping its spacing style (`key = value`). */
    private fun withValue(line: String, value: String): String {
        val eq = line.indexOf('=')
        val after = line.substring(eq + 1)
        val gap = after.takeWhile { it == ' ' || it == '\t' }
        return line.substring(0, eq + 1) + gap + value
    }

    fun get(text: String, section: String, key: String): String? {
        val lines = text.lines()
        val r = range(lines, section) ?: return null
        for (i in r) if (keyOf(lines[i]).equals(key, true)) return lines[i].substringAfter('=').trim()
        return null
    }

    /** First value of [key] anywhere in the file, in any section or none. */
    fun getAnywhere(text: String, key: String): String? =
        text.lines().firstOrNull { keyOf(it).equals(key, true) }?.substringAfter('=')?.trim()

    /** Changes the first existing [key] anywhere in the file. Returns null if the key isn't there. */
    fun setAnywhere(text: String, key: String, value: String): String? {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(Regex("\r?\n")).toMutableList()
        val i = lines.indexOfFirst { keyOf(it).equals(key, true) }
        if (i < 0) return null
        lines[i] = withValue(lines[i], value)
        return lines.joinToString(nl)
    }

    fun set(text: String, section: String, key: String, value: String): String {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(Regex("\r?\n")).toMutableList()
        if (lines.size == 1 && lines[0].isEmpty()) lines.clear()
        val r = range(lines, section)
        if (r == null) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            if (lines.isNotEmpty()) lines += ""
            lines += "[$section]"
            lines += "$key=$value"
            lines += ""
            return lines.joinToString(nl)
        }
        for (i in r) if (keyOf(lines[i]).equals(key, true)) {
            lines[i] = withValue(lines[i], value); return lines.joinToString(nl)
        }
        // Insert after the last non-blank line of the section.
        var at = r.last + 1
        while (at - 1 >= r.first && lines[at - 1].isBlank()) at--
        lines.add(at, "$key=$value")
        return lines.joinToString(nl)
    }

    fun remove(text: String, section: String, key: String): String {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(Regex("\r?\n")).toMutableList()
        val r = range(lines, section) ?: return text
        val idx = r.firstOrNull { keyOf(lines[it]).equals(key, true) } ?: return text
        lines.removeAt(idx)
        // Drop the section header too if it became empty.
        val r2 = range(lines, section)
        if (r2 != null && r2.all { lines[it].isBlank() }) {
            val h = r2.first - 1
            for (i in r2.last downTo h) lines.removeAt(i)
        }
        return lines.joinToString(nl)
    }
}
