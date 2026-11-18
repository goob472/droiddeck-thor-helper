package com.goob.ddhelper

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract

/**
 * An installed DroidDeck and where it keeps everything. [data] is DroidDeck's app folder exactly as
 * its documents provider reports it (normally /data/user/0/<package>), so paths here can be handed
 * straight to [Storage].
 */
class DroidDeck(val pkg: String, val versionName: String?, val data: String = "/data/user/0/$pkg") {
    val files = "$data/files"
    val envFile = "$files/game-environment.json"
    val linuxRoot = "$files/linuxfs"
    val steamRoots = listOf(
        "$linuxRoot/root/.local/share/Steam",
        "$linuxRoot/root/.droiddeck-x64/.local/share/Steam",
    )
    val protonGames = "$linuxRoot/root/.local/share/droiddeck-compat/games.json"

    fun withData(path: String) = DroidDeck(pkg, versionName, path.trimEnd('/'))

    /** DroidDeck's documents provider; the picker opens on this folder. */
    val authority get() = "$pkg.documents"
    val providerRoot: Uri get() = DocumentsContract.buildDocumentUri(authority, data)

    /** Maps a path from inside DroidDeck's Linux guest to the host paths it may correspond to. */
    fun guestToHost(p: String): List<String> {
        val path = p.trimEnd('/')
        val out = ArrayList<String>()
        when {
            path.startsWith("/root/Storage") -> out += "/storage/emulated/0" + path.removePrefix("/root/Storage")
            path.startsWith("/storage/") -> out += path
            path.startsWith("/sdcard") -> out += "/storage/emulated/0" + path.removePrefix("/sdcard")
            path.startsWith("/mnt/media_rw/") -> out += "/storage/" + path.removePrefix("/mnt/media_rw/")
            else -> out += linuxRoot + path
        }
        return out
    }

    /**
     * Steam libraries DroidDeck has registered outside its own folder (shared storage or SD card),
     * from libraryfolders.vdf. Each needs its own grant in the file picker before games there can be
     * found or changed.
     */
    fun externalLibraries(): List<String> {
        val found = LinkedHashSet<String>()
        for (sr in steamRoots) {
            for (f in listOf("$sr/steamapps/libraryfolders.vdf", "$sr/config/libraryfolders.vdf")) {
                val text = runCatching { Storage.readText(f) }.getOrNull() ?: continue
                Vdf.allValues(text, "path").flatMap(::guestToHost)
                    .filter { it.startsWith("/storage/") && it !in NOT_A_LIBRARY && FORBIDDEN_TREES.none { bad -> it == bad || it.startsWith("$bad/") } }
                    .forEach { found += it }
            }
        }
        return found.toList()
    }

    data class Install(val library: String, val gameDir: String, val prefix: String?)

    /** Why a game was or wasn't found, one line per library checked (shown in the game's details). */
    class ScanReport {
        val lines = ArrayList<String>()
        fun add(s: String) { lines += s }
        override fun toString() = lines.joinToString("\n")
    }

    fun findInstall(game: Game, report: ScanReport? = null): Install? =
        findInstall(game.steamAppId, game.installDir, game.detectFiles, report)

    /**
     * Finds this game's install inside DroidDeck: Steam's appmanifest for the app id, its install
     * folder under steamapps/common, and (when [detectFiles] are given) the game's executable inside
     * that folder, so an empty or half-deleted folder doesn't count as installed. Every library is
     * checked, DroidDeck's own and any granted external ones.
     */
    fun findInstall(appId: Long, fallbackDir: String, detectFiles: List<String> = emptyList(), report: ScanReport? = null): Install? {
        val libraries = steamRoots.filter { runCatching { Storage.isDir(it) }.getOrDefault(false) } +
            Scope.folders().drop(1)
        if (libraries.isEmpty()) report?.add("No Steam folder found inside DroidDeck (looked for ${steamRoots[0].removePrefix(data)}). Has Steam been set up in DroidDeck?")
        for (lib in libraries) {
            val short = lib.removePrefix(data).ifEmpty { lib }
            val manifest = "$lib/steamapps/appmanifest_$appId.acf"
            val acf = runCatching { Storage.readText(manifest) }.getOrNull()
            if (acf == null) {
                report?.add("✗ $short: no appmanifest_$appId.acf"); continue
            }
            val dirName = Vdf.get(acf, listOf("AppState"), "installdir") ?: fallbackDir
            if (dirName.contains('/') || dirName == "..") {
                report?.add("✗ $short: manifest has an unusable installdir"); continue
            }
            val common = "$lib/steamapps/common"
            val actual = runCatching { Storage.list(common) }.getOrDefault(emptyList())
                .firstOrNull { it.equals(dirName, ignoreCase = true) }
            if (actual == null) {
                report?.add("✗ $short: manifest found, but steamapps/common/$dirName is missing (still downloading?)"); continue
            }
            val gameDir = "$common/$actual"
            if (detectFiles.isNotEmpty() && detectFiles.none { f -> resolveIgnoringCase(gameDir, f) != null }) {
                report?.add("✗ $short: $actual is there, but ${detectFiles.joinToString(" / ")} isn't (incomplete install?)"); continue
            }
            val pfx = listOf("$lib/steamapps/compatdata/$appId/pfx", "${steamRoots[0]}/steamapps/compatdata/$appId/pfx")
                .firstOrNull { runCatching { Storage.isDir(it) }.getOrDefault(false) }
            report?.add("✓ $short: installed at steamapps/common/$actual" + if (pfx == null) " (no Proton prefix yet; launch once)" else "")
            return Install(lib, gameDir, pfx)
        }
        return null
    }

    /**
     * Finds [rel] under [dir] ignoring letter case, the way Windows (and so the game) sees its own
     * files on DroidDeck's case-sensitive Linux file system. Returns the real path of an existing file.
     */
    fun resolveIgnoringCase(dir: String, rel: String): String? {
        if (rel.isEmpty() || rel.startsWith("/") || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        if (runCatching { Storage.isFile("$dir/$rel") }.getOrDefault(false)) return "$dir/$rel"
        var cur = dir
        val parts = rel.split('/')
        for ((i, part) in parts.withIndex()) {
            val match = runCatching { Storage.list(cur) }.getOrDefault(emptyList()).firstOrNull { it.equals(part, ignoreCase = true) }
                ?: return null
            cur = "$cur/$match"
            if (i == parts.lastIndex) return cur.takeIf { runCatching { Storage.isFile(it) }.getOrDefault(false) }
        }
        return null
    }

    /** The game's portrait art from Steam's own library cache inside DroidDeck, if it has one. */
    fun findArt(appId: Long): String? {
        for (sr in steamRoots) {
            val cache = "$sr/appcache/librarycache"
            val perApp = "$cache/$appId"
            val names = runCatching { Storage.list(perApp) }.getOrDefault(emptyList())
            for (pat in listOf("library_600x900", "library_capsule")) {
                names.firstOrNull { it.startsWith(pat) && it.endsWith(".jpg") }?.let { return "$perApp/$it" }
                val flat = "$cache/${appId}_$pat.jpg"
                if (runCatching { Storage.isFile(flat) }.getOrDefault(false)) return flat
            }
        }
        return null
    }

    /** userdata/<account>/config/localconfig.vdf for every signed-in account under both Steam clients. */
    fun localConfigs(): List<String> = steamRoots.flatMap { sr ->
        runCatching { Storage.list("$sr/userdata") }.getOrDefault(emptyList())
            .filter { it.isNotEmpty() && it.all(Char::isDigit) && it != "0" }
            .map { "$sr/userdata/$it/config/localconfig.vdf" }
    }.filter { runCatching { Storage.isFile(it) }.getOrDefault(false) }

    companion object {
        val PACKAGES = listOf("com.droiddeck.launcher", "com.tencent.ig", "com.antutu.benchmark.full", "com.ludashi.benchmark")

        /** Never accepted as a Steam library, whatever a config file says: these exact paths... */
        private val NOT_A_LIBRARY = setOf("/storage", "/storage/emulated", "/storage/self")
        /** ...and anything inside these (other apps' data on shared storage). */
        private val FORBIDDEN_TREES = listOf("/storage/emulated/0/Android")

        /** Installed DroidDeck builds, the real package first. Needs no permission (package visibility). */
        fun detect(ctx: Context): List<DroidDeck> = PACKAGES.mapNotNull { pkg ->
            val info = try {
                ctx.packageManager.getPackageInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } ?: return@mapNotNull null
            DroidDeck(pkg, info.versionName)
        }
    }
}
