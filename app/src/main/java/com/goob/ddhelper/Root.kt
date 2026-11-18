package com.goob.ddhelper

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Thin wrapper over `su`. Scripts are fed on stdin so nothing needs shell-escaping twice.
 * Every path that goes into a script is passed through [q].
 */
object Root {
    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun run(script: String, timeoutSec: Long = 60): Result {
        return try {
            val p = ProcessBuilder("su").start()
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = thread { p.inputStream.bufferedReader().use { r -> r.forEachLine { out.appendLine(it) } } }
            val t2 = thread { p.errorStream.bufferedReader().use { r -> r.forEachLine { err.appendLine(it) } } }
            p.outputStream.bufferedWriter().use { w ->
                w.write(script)
                w.write("\nexit \$?\n")
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return Result(124, out.toString(), "timed out after ${timeoutSec}s")
            }
            t1.join(2000); t2.join(2000)
            Result(p.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) {
            Result(127, "", e.message ?: e.toString())
        }
    }

    fun available(): Boolean = run("id -u", 20).out.trim() == "0"

    fun exists(path: String) = run("[ -e ${q(path)} ]").ok
    fun isDir(path: String) = run("[ -d ${q(path)} ]").ok

    /** Returns file contents, or null if the file does not exist / cannot be read. */
    fun readText(ctx: Context, path: String): String? {
        val tmp = File(ctx.cacheDir, "read-${System.nanoTime()}.tmp")
        val r = run(
            "[ -f ${q(path)} ] || exit 3\n" +
                "cat ${q(path)} > ${q(tmp.absolutePath)} && chmod 0644 ${q(tmp.absolutePath)}"
        )
        if (!r.ok) {
            tmp.delete(); return null
        }
        return try {
            tmp.readText()
        } finally {
            run("rm -f ${q(tmp.absolutePath)}")
            tmp.delete()
        }
    }

    fun list(path: String): List<String> {
        val r = run("[ -d ${q(path)} ] && ls -1A ${q(path)}")
        return if (r.ok) r.out.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    /**
     * Writes [text] to [path] as root. An existing file is rewritten in place so it keeps its owner,
     * mode and SELinux label. A new file (and any directories created for it) takes the owner and
     * label of the nearest existing ancestor directory, so the owning app can still read it.
     */
    fun writeText(ctx: Context, path: String, text: String): Result {
        val tmp = File(ctx.cacheDir, "write-${System.nanoTime()}.tmp")
        tmp.writeText(text)
        tmp.setReadable(true, false)
        val script = """
            target=${q(path)}
            tmp=${q(tmp.absolutePath)}
            if [ -e "${'$'}target" ]; then
              cat "${'$'}tmp" > "${'$'}target" || exit 1
            else
              dir=${'$'}(dirname "${'$'}target")
              a="${'$'}dir"
              while [ ! -d "${'$'}a" ]; do a=${'$'}(dirname "${'$'}a"); done
              own=${'$'}(stat -c '%u:%g' "${'$'}a")
              lbl=${'$'}(stat -c '%C' "${'$'}a" 2>/dev/null)
              case "${'$'}lbl" in *:*) ;; *) lbl=${'$'}(ls -Zd "${'$'}a" | awk '{print ${'$'}1}') ;; esac
              mkdir -p "${'$'}dir" || exit 1
              cat "${'$'}tmp" > "${'$'}target" || exit 1
              chmod 0660 "${'$'}target"
              p="${'$'}target"
              while [ "${'$'}p" != "${'$'}a" ] && [ "${'$'}p" != "/" ]; do
                chown "${'$'}own" "${'$'}p"
                case "${'$'}lbl" in *:*) chcon "${'$'}lbl" "${'$'}p" 2>/dev/null ;; esac
                p=${'$'}(dirname "${'$'}p")
              done
            fi
        """.trimIndent()
        val r = run(script)
        tmp.delete()
        return r
    }

    fun rename(from: String, to: String) = run("mv -f ${q(from)} ${q(to)}")

    /** Copies a root-only file into this app's own storage so it can be read without root. */
    fun copyOut(path: String, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        return run("cat ${q(path)} > ${q(dest.absolutePath)} && chmod 0644 ${q(dest.absolutePath)}").ok && dest.length() > 0
    }

    fun copy(from: String, to: String) = run("mkdir -p ${q(File(to).parent!!)} && cp -p ${q(from)} ${q(to)}")
}
