package com.goob.ddhelper

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Fetches a mod from its author's official release. Nothing is bundled with this app: the user's own
 * device downloads the file, and it's only used if its SHA-256 matches the pinned value exactly.
 */
object ModDownload {
    private const val MAX_BYTES = 64L * 1024 * 1024

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The file's bytes if it downloaded and matches [expectedSha256], otherwise null. */
    fun fetch(url: String, expectedSha256: String): ByteArray? {
        require(url.startsWith("https://github.com/")) { "Mods are only downloaded from GitHub releases" }
        return try {
            var conn = URL(url).openConnection() as HttpURLConnection
            var hops = 0
            // Follow GitHub's redirect to its release storage (https only, at most 5 hops).
            while (conn.responseCode in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: return null
                conn.disconnect()
                if (!next.startsWith("https://")) return null
                conn = URL(next).openConnection() as HttpURLConnection
            }
            if (conn.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
            }
            conn.disconnect()
            out.toByteArray().takeIf { sha256(it) == expectedSha256.lowercase() }
        } catch (_: Exception) {
            null
        }
    }

    /** Safe relative path for a zip entry, or null for anything that could escape the game folder. */
    fun safeEntryName(name: String): String? {
        val n = name.replace('\\', '/')
        if (n.isEmpty() || n.startsWith("/") || n.endsWith("/") || ':' in n || '\u0000' in n) return null
        val parts = n.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return n
    }

    /** All files in the zip as (relative path, bytes). Folder entries are skipped. */
    fun unzip(zip: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val rel = safeEntryName(e.name) ?: throw SecurityException("Unsafe path in mod archive: ${e.name}")
                out += rel to z.readBytes()
            }
        }
        return out
    }
}
