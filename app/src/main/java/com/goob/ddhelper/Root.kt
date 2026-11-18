package com.goob.ddhelper

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Shell access, kept as narrow as possible.
 *
 * - Every file operation goes through [Scope]: paths outside DroidDeck's own folder and the Steam
 *   libraries DroidDeck has registered are refused before any command runs.
 * - When the root manager supports it (`su <uid>`), file work inside DroidDeck's folder runs as
 *   DroidDeck's own Android user, so the kernel itself stops it from reaching any other app's data.
 * - Full root is used only to detect DroidDeck's user id, to close DroidDeck before applying, and
 *   for Steam libraries on shared or SD-card storage that DroidDeck's user can't open from a shell.
 * - File contents travel through the shell as base64, so no temporary files are shared and the
 *   bytes (including line endings) come back exactly as they are on disk.
 */
object Root {
    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** DroidDeck's Linux user id, when `su <uid>` works; null means scoped full root is used. */
    @Volatile var droidDeckUid: Int? = null

    private fun exec(cmd: List<String>, script: String, timeoutSec: Long): Result {
        return try {
            val p = ProcessBuilder(cmd).start()
            val out = ByteArrayOutputStream()
            val err = StringBuilder()
            val t1 = thread { p.inputStream.use { it.copyTo(out) } }
            val t2 = thread { p.errorStream.bufferedReader().use { r -> r.forEachLine { err.appendLine(it) } } }
            p.outputStream.bufferedWriter().use { w ->
                w.write(script)
                w.write("\nexit \$?\n")
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return Result(124, out.toString(Charsets.UTF_8.name()), "timed out after ${timeoutSec}s")
            }
            t1.join(5000); t2.join(2000)
            Result(p.exitValue(), out.toString(Charsets.UTF_8.name()), err.toString())
        } catch (e: Exception) {
            Result(127, "", e.message ?: e.toString())
        }
    }

    /** Full root. Only for the few fixed commands listed in the class comment. */
    fun runAsRoot(script: String, timeoutSec: Long = 60) = exec(listOf("su"), script, timeoutSec)

    /** Runs a file command touching [paths]: as DroidDeck's user when they are all inside its folder. */
    private fun runScoped(paths: List<String>, script: String, timeoutSec: Long = 60): Result {
        paths.forEach(Scope::check)
        val uid = droidDeckUid
        return if (uid != null && paths.all(Scope::isInsideDroidDeck)) exec(listOf("su", uid.toString()), script, timeoutSec)
        else runAsRoot(script, timeoutSec)
    }

    fun available(): Boolean = runAsRoot("id -u", 20).out.trim() == "0"

    /** Finds DroidDeck's uid and checks that `su <uid>` really drops to it. */
    fun setupDroidDeckUser(dataDir: String) {
        droidDeckUid = null
        val uid = runAsRoot("stat -c %u ${q(dataDir)}").out.trim().toIntOrNull() ?: return
        if (uid < 10000) return
        val r = exec(listOf("su", uid.toString()), "id -u", 20)
        if (r.ok && r.out.trim() == uid.toString()) droidDeckUid = uid
    }

    fun exists(path: String) = runScoped(listOf(path), "[ -e ${q(path)} ]").ok
    fun isDir(path: String) = runScoped(listOf(path), "[ -d ${q(path)} ]").ok

    fun list(path: String): List<String> {
        val r = runScoped(listOf(path), "[ -d ${q(path)} ] && ls -1A ${q(path)}")
        return if (r.ok) r.out.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    /** Exact file bytes, or null if the file doesn't exist or can't be read. */
    fun readBytes(path: String): ByteArray? {
        val r = runScoped(listOf(path), "[ -f ${q(path)} ] || exit 3\nbase64 ${q(path)}", 120)
        if (!r.ok) return null
        return runCatching { Base64.getMimeDecoder().decode(r.out.trim()) }.getOrNull()
    }

    fun readText(path: String): String? = readBytes(path)?.toString(Charsets.UTF_8)

    @Suppress("UNUSED_PARAMETER")
    fun readText(ctx: Context, path: String): String? = readText(path)

    fun isFile(path: String) = runScoped(listOf(path), "[ -f ${q(path)} ]").ok

    /**
     * Writes [text] to [path]. An existing file is rewritten in place so it keeps its owner, mode and
     * SELinux label. A new file (and any directories created for it) takes the owner and label of the
     * nearest existing folder, so the game or DroidDeck can still read it.
     */
    fun writeText(@Suppress("UNUSED_PARAMETER") ctx: Context, path: String, text: String): Result =
        // The temp file sits next to the target, so it is inside the same allowed folder.
        runScoped(listOf(path, "$path.ddh-tmp"), writeScript(path, text.toByteArray(Charsets.UTF_8)))

    /** The shell script behind [writeText]. Separate so it can be run against a real device in tests. */
    internal fun writeScript(path: String, bytes: ByteArray): String {
        val b64 = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(bytes)
        val d = "$"
        // 1. Find the nearest existing folder, remember its owner and SELinux label, create the rest.
        val prepare = """
            target=${q(path)}
            tmp="${d}target.ddh-tmp"
            dir=${d}(dirname "${d}target")
            a="${d}dir"
            while [ ! -d "${d}a" ]; do a=${d}(dirname "${d}a"); done
            own=${d}(stat -c '%u:%g' "${d}a")
            lbl=${d}(stat -c '%C' "${d}a" 2>/dev/null)
            case "${d}lbl" in *:*) ;; *) lbl=${d}(ls -Zd "${d}a" | awk '{print ${d}1}') ;; esac
            mkdir -p "${d}dir" || exit 1
            export TMPDIR="${d}dir"
        """.trimIndent()
        // TMPDIR: the shell keeps heredoc data in a temp file, by default in /data/local, which
        // DroidDeck's own user can't write. The target folder is inside the allowed area.
        // 2. Decode the exact bytes. Heredoc lines must start at column 0, so no trimIndent here.
        val decode = "base64 -d > \"${d}tmp\" <<'DDH_B64_END' || { rm -f \"${d}tmp\"; exit 1; }\n" +
            (if (b64.isEmpty()) "" else b64 + "\n") +
            "DDH_B64_END\n"
        // 3. Rewrite an existing file in place (keeps owner, mode, label), or move a new one into place
        //    and give it, and any folders created for it, the nearest existing folder's owner and label.
        val place = """
            if [ -e "${d}target" ]; then
              cat "${d}tmp" > "${d}target" || { rm -f "${d}tmp"; exit 1; }
              rm -f "${d}tmp"
            else
              mv -f "${d}tmp" "${d}target" || { rm -f "${d}tmp"; exit 1; }
              chmod 0660 "${d}target"
              p="${d}target"
              while [ "${d}p" != "${d}a" ] && [ "${d}p" != "/" ]; do
                chown "${d}own" "${d}p" 2>/dev/null
                case "${d}lbl" in *:*) chcon "${d}lbl" "${d}p" 2>/dev/null ;; esac
                p=${d}(dirname "${d}p")
              done
            fi
        """.trimIndent()
        return prepare + "\n" + decode + place + "\n"
    }

    fun rename(from: String, to: String) = runScoped(listOf(from, to), "mv -f ${q(from)} ${q(to)}")

    /** Lists matching files under an allowed folder (used to find cached cover art). */
    fun find(dir: String, namePattern: String): List<String> {
        val r = runScoped(listOf(dir), "[ -d ${q(dir)} ] && find ${q(dir)} -name ${q(namePattern)} 2>/dev/null")
        return r.out.lines().map { it.trim() }.filter { it.isNotEmpty() && Scope.allows(it) }
    }
}
