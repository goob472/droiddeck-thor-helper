package com.goob.ddhelper

import android.content.Context
import android.content.pm.PackageManager

/** Where an installed DroidDeck keeps everything (paths as seen by root on the host). */
class DroidDeck(val pkg: String, val versionName: String?) {
    val data = "/data/data/$pkg"
    val files = "$data/files"
    val envFile = "$files/game-environment.json"
    val linuxRoot = "$files/linuxfs"
    val steamRoots = listOf(
        "$linuxRoot/root/.local/share/Steam",
        "$linuxRoot/root/.droiddeck-x64/.local/share/Steam",
    )
    val protonGames = "$linuxRoot/root/.local/share/droiddeck-compat/games.json"

    /** Maps a path from inside DroidDeck's Linux guest to the host path root can open. */
    fun guestToHost(p: String): List<String> {
        val path = p.trimEnd('/')
        val out = ArrayList<String>()
        fun storage(rest: String) {
            // Internal storage: FUSE view and the backing directory root always sees.
            out += "/data/media/0$rest"; out += "/storage/emulated/0$rest"
        }
        when {
            path.startsWith("/root/Storage") -> storage(path.removePrefix("/root/Storage"))
            path.startsWith("/storage/emulated/0") -> storage(path.removePrefix("/storage/emulated/0"))
            path.startsWith("/sdcard") -> storage(path.removePrefix("/sdcard"))
            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                out += "/mnt/media_rw/$rest"; out += path
            }
            else -> {
                out += linuxRoot + path; out += path
            }
        }
        return out
    }

    data class Install(val library: String, val gameDir: String, val prefix: String?)

    /** Finds the game through Steam's libraryfolders.vdf + appmanifest, like Steam does. */
    fun findInstall(appId: Long, fallbackDir: String): Install? {
        val libraries = LinkedHashSet<String>()
        for (sr in steamRoots) {
            if (!Root.isDir(sr)) continue
            libraries += sr
            for (f in listOf("$sr/steamapps/libraryfolders.vdf", "$sr/config/libraryfolders.vdf")) {
                val text = Root.run("cat ${Root.q(f)} 2>/dev/null").out
                if (text.isBlank()) continue
                Vdf.allValues(text, "path").forEach { libraries += guestToHost(it) }
            }
        }
        for (lib in libraries) {
            val manifest = "$lib/steamapps/appmanifest_$appId.acf"
            val acf = Root.run("cat ${Root.q(manifest)} 2>/dev/null").out
            if (acf.isBlank()) continue
            val dirName = Vdf.get(acf, listOf("AppState"), "installdir") ?: fallbackDir
            val gameDir = "$lib/steamapps/common/$dirName"
            if (!Root.isDir(gameDir)) continue
            val pfx = listOf("$lib/steamapps/compatdata/$appId/pfx", "${steamRoots[0]}/steamapps/compatdata/$appId/pfx")
                .firstOrNull { Root.isDir(it) }
            return Install(lib, gameDir, pfx)
        }
        return null
    }

    /**
     * The game's library art from Steam's own cache inside DroidDeck: the portrait capsule if there is
     * one, otherwise the header. Handles both the per-app folder layout and the older flat one.
     */
    fun findArt(appId: Long): String? {
        val dirs = steamRoots.joinToString(" ") { Root.q("$it/appcache/librarycache") }
        val script = """
            for d in $dirs; do
              [ -d "${'$'}d" ] || continue
              for pat in library_600x900 library_capsule header; do
                f=${'$'}(find "${'$'}d/$appId" -name "${'$'}pat*.jpg" 2>/dev/null | head -n 1)
                [ -n "${'$'}f" ] && { echo "${'$'}f"; exit 0; }
                [ -f "${'$'}d/${appId}_${'$'}pat.jpg" ] && { echo "${'$'}d/${appId}_${'$'}pat.jpg"; exit 0; }
              done
            done
            exit 1
        """.trimIndent()
        val r = Root.run(script)
        return r.out.trim().lineSequence().firstOrNull()?.takeIf { r.ok && it.isNotEmpty() }
    }

    /** userdata/<account>/config/localconfig.vdf for every signed-in account under both Steam clients. */
    fun localConfigs(): List<String> = steamRoots.flatMap { sr ->
        Root.list("$sr/userdata").filter { it.all(Char::isDigit) && it != "0" }
            .map { "$sr/userdata/$it/config/localconfig.vdf" }
    }.filter { Root.exists(it) }

    companion object {
        val PACKAGES = listOf("com.droiddeck.launcher", "com.tencent.ig", "com.antutu.benchmark.full", "com.ludashi.benchmark")

        /** Installed DroidDeck builds, the real package first. Only counts ones with a Linux runtime. */
        fun detect(ctx: Context): List<DroidDeck> = PACKAGES.mapNotNull { pkg ->
            val info = try {
                ctx.packageManager.getPackageInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } ?: return@mapNotNull null
            val dd = DroidDeck(pkg, info.versionName)
            if (pkg == "com.droiddeck.launcher" || Root.isDir(dd.linuxRoot)) dd else null
        }
    }
}
