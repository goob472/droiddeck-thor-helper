package com.goob.ddhelper

/**
 * Minimal in-place editor for Valve's text KeyValues (.vdf) files. It edits the original text
 * rather than re-serialising, so everything it does not touch stays byte-for-byte the same.
 */
object Vdf {
    private class Node(
        val key: String,
        val keyStart: Int,
        /** For a value: index of its opening quote. For a section: index of '{'. */
        val valueStart: Int,
        /** For a value: index of its closing quote. For a section: index of '}'. */
        val valueEnd: Int,
        val section: Boolean,
    )

    private fun stringEnd(t: String, open: Int): Int? {
        var i = open + 1
        while (i < t.length) {
            when (t[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun sectionEnd(t: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < t.length) {
            when {
                t[i] == '"' -> i = stringEnd(t, i) ?: return null
                t.startsWith("//", i) -> i = t.indexOf('\n', i).let { if (it < 0) t.length else it }
                t[i] == '{' -> depth++
                t[i] == '}' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    /** Direct children of the region (open, close). Use open = -1, close = length for top level. */
    private fun children(t: String, open: Int, close: Int): List<Node> {
        val found = ArrayList<Node>()
        var key: String? = null
        var keyStart = -1
        var i = open + 1
        while (i < close) {
            when {
                t[i] == '"' -> {
                    val end = stringEnd(t, i) ?: return found
                    if (key == null) {
                        key = unescape(t.substring(i + 1, end)); keyStart = i
                    } else {
                        found += Node(key, keyStart, i, end, false); key = null
                    }
                    i = end + 1
                }
                t[i] == '{' -> {
                    val end = sectionEnd(t, i) ?: return found
                    key?.let { found += Node(it, keyStart, i, end, true) }
                    key = null
                    i = end + 1
                }
                t.startsWith("//", i) -> i = t.indexOf('\n', i).let { if (it < 0) close else it }
                else -> i++
            }
        }
        return found
    }

    private fun find(t: String, path: List<String>): Node? {
        var node: Node? = null
        for (k in path) {
            node = children(t, node?.valueStart ?: -1, node?.valueEnd ?: t.length)
                .firstOrNull { it.section && it.key.equals(k, ignoreCase = true) } ?: return null
        }
        return node
    }

    fun get(t: String, sectionPath: List<String>, key: String): String? {
        val s = find(t, sectionPath) ?: return null
        return children(t, s.valueStart, s.valueEnd).firstOrNull { !it.section && it.key.equals(key, true) }
            ?.let { unescape(t.substring(it.valueStart + 1, it.valueEnd)) }
    }

    fun hasSection(t: String, sectionPath: List<String>) = find(t, sectionPath) != null

    /** Indentation used by the closing brace of a section, if it sits on its own line. */
    private fun closingIndent(t: String, s: Node): Pair<Int, String> {
        val lineStart = t.lastIndexOf('\n', s.valueEnd - 1) + 1
        val before = t.substring(lineStart, s.valueEnd)
        return if (lineStart > s.valueStart && before.all { it == '\t' || it == ' ' }) lineStart to before
        else s.valueEnd to ""
    }

    private fun ensureSection(t: String, parentPath: List<String>, key: String, nl: String): String? {
        val parent = find(t, parentPath) ?: return null
        if (children(t, parent.valueStart, parent.valueEnd).any { it.section && it.key.equals(key, true) }) return t
        val (insertAt, indent) = closingIndent(t, parent)
        val ind = indent + "\t"
        val block = "$ind\"${escape(key)}\"$nl$ind{$nl$ind}$nl"
        return if (insertAt == parent.valueEnd) t.substring(0, insertAt) + nl + block + t.substring(insertAt)
        else t.substring(0, insertAt) + block + t.substring(insertAt)
    }

    /**
     * Sets [key] = [value] inside [sectionPath], creating missing sections below [mustExist]
     * (the leading part of the path that has to be there already). Returns null if the file does
     * not have the expected structure.
     */
    fun set(t: String, sectionPath: List<String>, mustExist: Int, key: String, value: String): String? {
        val nl = if (t.contains("\r\n")) "\r\n" else "\n"
        if (find(t, sectionPath.take(mustExist)) == null) return null
        var text = t
        for (i in mustExist until sectionPath.size) {
            text = ensureSection(text, sectionPath.take(i), sectionPath[i], nl) ?: return null
        }
        val s = find(text, sectionPath) ?: return null
        val quoted = "\"" + escape(value) + "\""
        children(text, s.valueStart, s.valueEnd).firstOrNull { !it.section && it.key.equals(key, true) }?.let {
            return text.replaceRange(it.valueStart, it.valueEnd + 1, quoted)
        }
        val (insertAt, indent) = closingIndent(text, s)
        val line = "$indent\t\"${escape(key)}\"\t\t$quoted$nl"
        return if (insertAt == s.valueEnd) text.substring(0, insertAt) + nl + line + text.substring(insertAt)
        else text.substring(0, insertAt) + line + text.substring(insertAt)
    }

    /** Removes [key] from [sectionPath] if present. */
    fun remove(t: String, sectionPath: List<String>, key: String): String {
        val s = find(t, sectionPath) ?: return t
        val e = children(t, s.valueStart, s.valueEnd).firstOrNull { !it.section && it.key.equals(key, true) } ?: return t
        val lineStart = t.lastIndexOf('\n', e.keyStart - 1) + 1
        val lineEnd = t.indexOf('\n', e.valueEnd + 1).let { if (it < 0) t.length else it }
        val alone = t.substring(lineStart, e.keyStart).isBlank() && t.substring(e.valueEnd + 1, lineEnd).isBlank()
        return if (alone) t.removeRange(lineStart, if (lineEnd < t.length) lineEnd + 1 else lineEnd)
        else t.removeRange(e.keyStart, e.valueEnd + 1)
    }

    /** All string values named [key] anywhere in the file (used for libraryfolders "path"). */
    fun allValues(t: String, key: String): List<String> {
        val out = ArrayList<String>()
        fun walk(open: Int, close: Int) {
            for (n in children(t, open, close)) {
                if (n.section) walk(n.valueStart, n.valueEnd)
                else if (n.key.equals(key, true)) out += unescape(t.substring(n.valueStart + 1, n.valueEnd))
            }
        }
        walk(-1, t.length)
        return out
    }

    private fun escape(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun unescape(v: String) = v.replace(Regex("\\\\(.)"), "$1")
}
