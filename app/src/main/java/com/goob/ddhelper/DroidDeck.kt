package com.goob.ddhelper

import android.content.Context
import android.content.pm.PackageManager

/** Where an installed DroidDeck keeps everything (paths as seen from a root shell on the host). */
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

    /** Maps a path from inside DroidDeck's Linux guest to the host paths it may correspond to. */
    fun guestToHost(p: String): List<String> {
        val path = p.trimEnd('/')
        val out = ArrayList<String>()
        fun storage(rest: String) {
            // Internal shared storage: the FUSE view and the backing folder root always sees.
            out += "/storage/emulated/0$rest"; out += "/data/media/0$rest"
        }
        when {
            path.startsWith("/root/Storage") -> storage(path.removePrefix("/root/Storage"))
            path.startsWith("/storage/emulated/0") -> storage(path.removePrefix("/storage/emulated/0"))
            path.startsWith("/sdcard") -> storage(path.removePrefix("/sdcard"))
            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                out += path; out += "/mnt/media_rw/$rest"
            }
            else -> out += linuxRoot + path
        }
        return out
    }

    /**
     * Grants this app access to DroidDeck's folder only: sets the allowed paths, then adds the Steam
     * library folders DroidDeck has registered (for games on shared or SD-card storage), keeping only
     * ones that really are Steam libraries.
     */
    fun grantScopedAccess() {
        Scope.setDroidDeck(pkg)
        Root.setupDroidDeckUser(data)
        val candidates = LinkedHashSet<String>()
        for (sr in steamRoots) {
            for (f in listOf("$sr/steamapps/libraryfolders.vdf", "$sr/config/libraryfolders.vdf")) {
                val text = runCatching { Root.readText(f) }.getOrNull() ?: continue
                Vdf.allValues(text, "path").flatMap(::guestToHost).forEach { candidates += it }
            }
        }
        val safe = candidates.filter { c -> FORBIDDEN.none { c == it || c.startsWith("$it/") } && !Scope.isInsideDroidDeck(c) }
        // Allow them just long enough to check each one has a steamapps folder, then keep only those.
        Scope.setLibraries(safe)
        Scope.setLibraries(safe.filter { runCatching { Root.isDir("$it/steamapps") }.getOrDefault(false) })
    }

    data class Install(val library: String, val gameDir: String, val prefix: String?)

    /**
     * Finds this specific game's install inside DroidDeck: Steam's appmanifest for the app id, its
     * install folder under steamapps/common, and (when [detectFiles] are given) the game's executable
     * inside that folder, so an empty or half-deleted folder doesn't count as installed.
     */
    fun findInstall(game: Game): Install? = findInstall(game.steamAppId, game.installDir, game.detectFiles)

    fun findInstall(appId: Long, fallbackDir: String, detectFiles: List<String> = emptyList()): Install? {
        val libraries = steamRoots.filter { runCatching { Root.isDir(it) }.getOrDefault(false) } + Scope.folders().drop(1)
        for (lib in libraries) {
            val acf = runCatching { Root.readText("$lib/steamapps/appmanifest_$appId.acf") }.getOrNull() ?: continue
            val dirName = Vdf.get(acf, listOf("AppState"), "installdir") ?: fallbackDir
            if (dirName.contains('/') || dirName == "..") continue
            val gameDir = "$lib/steamapps/common/$dirName"
            if (!runCatching { Root.isDir(gameDir) }.getOrDefault(false)) continue
            if (detectFiles.isNotEmpty() &&
                detectFiles.none { f -> !f.contains("..") && runCatching { Root.isFile("$gameDir/$f") }.getOrDefault(false) }
            ) continue
            val pfx = listOf("$lib/steamapps/compatdata/$appId/pfx", "${steamRoots[0]}/steamapps/compatdata/$appId/pfx")
                .firstOrNull { runCatching { Root.isDir(it) }.getOrDefault(false) }
            return Install(lib, gameDir, pfx)
        }
        return null
    }

    /** The game's portrait art from Steam's own library cache inside DroidDeck, if it has one. */
    fun findArt(appId: Long): String? {
        for (sr in steamRoots) {
            val cache = "$sr/appcache/librarycache"
            for (pat in listOf("library_600x900", "library_capsule")) {
                runCatching { Root.find("$cache/$appId", "$pat*.jpg") }.getOrNull()?.firstOrNull()?.let { return it }
                val flat = "$cache/${appId}_$pat.jpg"
                if (runCatching { Root.exists(flat) }.getOrDefault(false)) return flat
            }
        }
        return null
    }

    /** userdata/<account>/config/localconfig.vdf for every signed-in account under both Steam clients. */
    fun localConfigs(): List<String> = steamRoots.flatMap { sr ->
        runCatching { Root.list("$sr/userdata") }.getOrDefault(emptyList())
            .filter { it.isNotEmpty() && it.all(Char::isDigit) && it != "0" }
            .map { "$sr/userdata/$it/config/localconfig.vdf" }
    }.filter { runCatching { Root.exists(it) }.getOrDefault(false) }

    companion object {
        val PACKAGES = listOf("com.droiddeck.launcher", "com.tencent.ig", "com.antutu.benchmark.full", "com.ludashi.benchmark")

        /** Never allowed as a "Steam library", whatever a config file says. */
        private val FORBIDDEN = listOf(
            "/", "/data", "/data/data", "/data/user", "/data/user_de", "/data/app", "/data/system", "/data/misc",
            "/data/adb", "/data/local", "/system", "/vendor", "/product", "/apex", "/proc", "/sys", "/dev",
            "/storage/emulated/0/Android", "/data/media/0/Android",
        )

        /** Installed DroidDeck builds, the real package first. Needs no root (package visibility). */
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
