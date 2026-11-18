package com.goob.ddhelper

/**
 * The only places this app may read or write: DroidDeck's own data folder, plus the Steam library
 * folders DroidDeck itself has registered (games installed to shared or SD-card storage). Every
 * shell file operation checks its paths here first, so a bug or a crafted game entry can't make the
 * app touch anything else.
 */
object Scope {
    @Volatile private var droidDeckDirs: List<String> = emptyList()
    @Volatile private var libraries: List<String> = emptyList()

    fun setDroidDeck(pkg: String) {
        // /data/data/<pkg> and /data/user/0/<pkg> are the same folder on every Android version.
        droidDeckDirs = listOf("/data/data/$pkg", "/data/user/0/$pkg")
        libraries = emptyList()
    }

    /** Adds Steam library roots that DroidDeck's libraryfolders.vdf lists. */
    fun setLibraries(paths: Collection<String>) {
        libraries = paths.map { it.trimEnd('/') }.filter { valid(it) && it.count { c -> c == '/' } >= 3 }.distinct()
    }

    fun folders(): List<String> = droidDeckDirs.take(1) + libraries

    private fun valid(path: String) =
        path.startsWith("/") && '\u0000' !in path && '\n' !in path &&
            path.split('/').none { it == ".." || it == "." }

    private fun under(path: String, root: String) = path == root || path.startsWith("$root/")

    fun isInsideDroidDeck(path: String) = valid(path) && droidDeckDirs.any { under(path, it) }

    fun allows(path: String) = valid(path) && (droidDeckDirs + libraries).any { under(path, it) }

    fun check(path: String) {
        if (droidDeckDirs.isEmpty()) throw SecurityException("DroidDeck folder access hasn't been granted yet.")
        if (!allows(path)) throw SecurityException("Refused to touch $path: it is outside DroidDeck's folder and its Steam libraries.")
    }
}
