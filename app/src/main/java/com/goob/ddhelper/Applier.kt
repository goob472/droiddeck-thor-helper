package com.goob.ddhelper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Applies and reverts a game profile. Everything it changes is recorded (with the previous value)
 * in files/applied/<game>.json, so revert puts back exactly what was there, and re-applying a
 * different profile always starts from the user's own settings.
 */
class Applier(private val ctx: Context, private val dd: DroidDeck, private val log: (String) -> Unit) {

    private val STEAM_APPS = listOf("UserLocalConfigStore", "Software", "Valve", "Steam", "apps")

    /** settings keep the original file name so state from older versions still reverts. */
    private fun stateFile(game: Game, kind: String) = File(
        File(ctx.filesDir, "applied").apply { mkdirs() },
        if (kind == Pack.SETTINGS) "${game.id}.json" else "${game.id}.$kind.json",
    )

    fun appliedState(game: Game, kind: String = Pack.SETTINGS): JSONObject? =
        stateFile(game, kind).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    private fun fail(msg: String): Nothing = throw IllegalStateException(msg)

    private fun write(path: String, text: String) {
        val r = Root.writeText(ctx, path, text)
        if (!r.ok) fail("Could not write $path: ${r.err.trim()}")
    }

    fun stopDroidDeck() {
        log("Stopping ${dd.pkg} so it cannot overwrite the files…")
        // The one full-root command: closing DroidDeck so it can't overwrite the files mid-change.
        Root.runAsRoot("am force-stop ${Root.q(dd.pkg)}")
        Thread.sleep(800)
    }

    private fun backup(paths: List<String>) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(ctx.filesDir, "backups/$stamp")
        for (p in paths) {
            val bytes = runCatching { Root.readBytes(p) }.getOrNull() ?: continue
            val dest = File(dir, p.trimStart('/').replace('/', '_'))
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            log("Backed up $p")
        }
        log("Backups: ${dir.absolutePath}")
    }

    fun apply(game: Game, pack: Pack) {
        if (pack.manual != null) fail("${pack.name} has to be changed inside the game.")
        if (!Root.available()) fail("Root access was denied.")
        if (dd.findInstall(game) == null) fail("${game.name} isn't installed in DroidDeck, so nothing was changed.")
        stopDroidDeck()
        backup(listOf(dd.envFile) + dd.localConfigs())
        if (appliedState(game, pack.kind) != null) {
            log("${pack.name} is already applied. Reverting it first so it starts from your own settings.")
            revertInternal(game, pack.kind)
        }
        val state = JSONObject()
            .put("game", game.id).put("kind", pack.kind).put("profile", pack.name).put("pkg", dd.pkg)
            .put("appliedAt", System.currentTimeMillis())
        val scope = game.steamAppId.toString()

        // 1. Per-game environment (FEX preset + fixes).
        val entries = pack.env
        if (entries.isNotEmpty()) {
        val envText = Root.readText(ctx, dd.envFile)
        if (envText == null) log("No game-environment.json yet; creating one.")
        val env = EnvFile.parseOrNew(envText)
        val prev = EnvFile.apply(env, scope, entries)
        write(dd.envFile, EnvFile.encode(env))
        state.put("env", JSONObject().apply { prev.forEach { (k, p) -> put(k, p.toJson()) } })
        entries.forEach { (k, v) -> log("  env $k = ${v ?: "(unset)"}") }
        log("Wrote per-game environment for app $scope.")
        }
        // Save progress now so a failure later can still be reverted.
        stateFile(game, pack.kind).writeText(state.toString(2))

        // 2. Steam launch options.
        if (pack.launchOptions != null) {
            val configs = dd.localConfigs()
            if (configs.isEmpty()) log("No Steam localconfig.vdf found. Sign in to Steam in DroidDeck once, then apply again to set launch options.")
            val launch = JSONObject()
            for (cfg in configs) {
                val text = Root.readText(ctx, cfg) ?: continue
                val path = STEAM_APPS + scope
                val old = Vdf.get(text, path, "LaunchOptions")
                val merged = mergeLaunchOptions(old, pack.launchOptions)
                val updated = Vdf.set(text, path, 4, "LaunchOptions", merged)
                if (updated == null) {
                    log("Skipped $cfg (unexpected layout)."); continue
                }
                write(cfg, updated)
                launch.put(cfg, if (old == null) JSONObject().put("absent", true) else JSONObject().put("value", old))
                log("Launch options: \"$merged\" (${cfg.substringAfter("/userdata/").substringBefore('/')})")
            }
            state.put("launch", launch)
            stateFile(game, pack.kind).writeText(state.toString(2))
        }

        // 3. Game-file tweaks.
        val install = if (pack.tweaks.isEmpty()) null else dd.findInstall(game)
        if (pack.tweaks.isNotEmpty()) {
            if (install == null) log("${game.name} is not installed in any Steam library DroidDeck uses. Skipping game-file tweaks.")
            else log("Found ${game.name} at ${install.gameDir}")
        }
        val tweaksState = JSONObject()
        for (t in pack.tweaks) {
            if (install == null) break
            try {
                when (t.type) {
                    "renameFiles" -> tweaksState.put(t.id, renameFiles(install, t))
                    "iniMerge" -> iniMerge(install, t)?.let { tweaksState.put(t.id, it) }
                    "wineReg" -> wineReg(install, t)?.let { tweaksState.put(t.id, it) }
                    "iniKey" -> iniKey(install, t)?.let { tweaksState.put(t.id, it) }
                    "modZip" -> modZip(game, install, t)?.let { tweaksState.put(t.id, it) }
                    else -> log("Unknown tweak type ${t.type}")
                }
            } catch (e: Exception) {
                log("Tweak '${t.name}' failed: ${e.message}")
            }
            state.put("tweaks", tweaksState)
            stateFile(game, pack.kind).writeText(state.toString(2))
        }
        state.put("tweaks", tweaksState)
        if (install != null) state.put("install", install.gameDir).put("prefix", install.prefix ?: JSONObject.NULL)
        stateFile(game, pack.kind).writeText(state.toString(2))
        log("Done: ${pack.name} applied. Open DroidDeck and launch ${game.name}. The changes apply from the next game launch.")
    }

    private fun renameFiles(install: DroidDeck.Install, t: Tweak): JSONObject {
        val dir = install.gameDir + "/" + t.json.getString("dir")
        val prefixes = t.json.getJSONArray("prefixes").let { a -> (0 until a.length()).map { a.getString(it) } }
        val suffix = t.json.optString("suffix")
        val renamed = JSONArray()
        for (name in Root.list(dir)) {
            if (prefixes.none { name.startsWith(it, true) } || !name.endsWith(suffix, true)) continue
            if (Root.rename("$dir/$name", "$dir/$name$BAK").ok) {
                renamed.put(name); log("  renamed $name")
            }
        }
        if (renamed.length() == 0) log("  ${t.name}: nothing to rename in $dir")
        return JSONObject().put("dir", dir).put("files", renamed)
    }

    private fun iniPath(install: DroidDeck.Install, raw: String): String? = when {
        raw.startsWith("pfx:") -> install.prefix?.let { it + "/" + raw.removePrefix("pfx:") }
        raw.startsWith("game:") -> install.gameDir + "/" + raw.removePrefix("game:")
        else -> raw
    }

    private fun iniMerge(install: DroidDeck.Install, t: Tweak): JSONObject? {
        val path = iniPath(install, t.json.getString("path"))
        if (path == null) {
            log("  ${t.name}: no Proton prefix yet. Launch the game once, then apply again."); return null
        }
        val existing = Root.readText(ctx, path)
        if (existing == null && t.json.optBoolean("requireExisting")) {
            log("  ${t.name}: ${path.substringAfterLast('/')} doesn't exist yet. Launch the game once, quit, then apply again.")
            return null
        }
        var text = existing ?: ""
        val sections = t.json.getJSONObject("sections")
        val prev = JSONObject()
        for (sec in sections.keys()) {
            val kv = sections.getJSONObject(sec)
            val secPrev = JSONObject()
            for (k in kv.keys()) {
                val old = Ini.get(text, sec, k)
                secPrev.put(k, if (old == null) JSONObject().put("absent", true) else JSONObject().put("value", old))
                text = Ini.set(text, sec, k, kv.getString(k))
                log("  [$sec] $k=${kv.getString(k)}")
            }
            prev.put(sec, secPrev)
        }
        write(path, text)
        return JSONObject().put("path", path).put("prev", prev)
    }

    /**
     * Changes one key that already exists anywhere in a config file the game wrote, keeping the line's
     * spacing. Nothing is added: if the file or key isn't there yet, the game hasn't created it.
     */
    private fun iniKey(install: DroidDeck.Install, t: Tweak): JSONObject? {
        val raw = t.json.getString("path")
        val path = when {
            raw.startsWith("pfx:") -> install.prefix?.let { dd.resolveIgnoringCase(it, raw.removePrefix("pfx:")) }
            raw.startsWith("game:") -> dd.resolveIgnoringCase(install.gameDir, raw.removePrefix("game:"))
            else -> null
        }
        val text = path?.let { Root.readText(it) }
        if (path == null || text == null) {
            log("  ${t.name}: the game hasn't created its settings file yet. Launch it once, quit, then apply again.")
            return null
        }
        val key = t.json.getString("key")
        val value = t.json.getString("value")
        val old = Ini.getAnywhere(text, key)
        val updated = Ini.setAnywhere(text, key, value)
        if (old == null || updated == null) {
            log("  ${t.name}: $key isn't in ${path.substringAfterLast('/')}, left alone."); return null
        }
        write(path, updated)
        log("  $key: $old -> $value")
        return JSONObject().put("iniKeyPath", path).put("key", key).put("old", old)
    }

    /**
     * Installs a mod from its author's official release: downloads the zip, checks its SHA-256 against
     * the pinned value, and copies its files into the game folder. Any file it would replace is backed
     * up first, and every file and folder it adds is recorded so revert removes exactly those.
     */
    private fun modZip(game: Game, install: DroidDeck.Install, t: Tweak): JSONObject? {
        val url = t.json.getString("url")
        val sha = t.json.getString("sha256").lowercase()
        log("  Downloading ${url.substringAfterLast('/')}…")
        val zip = ModDownload.fetch(url, sha) ?: run {
            log("  Download failed or the file didn't match its checksum, so nothing was installed."); return null
        }
        val entries = ModDownload.unzip(zip)
        val backupDir = File(ctx.filesDir, "mod-backups/${game.id}/${t.id}").apply { deleteRecursively(); mkdirs() }
        val created = JSONArray()
        val replaced = JSONArray()
        val dirs = JSONArray()
        val record = JSONObject().put("modDir", install.gameDir).put("created", created).put("replaced", replaced).put("dirs", dirs)
        try {
            for ((rel, bytes) in entries) {
                val target = "${install.gameDir}/$rel"
                // Remember folders this mod creates, deepest last, so revert can remove them if empty.
                var parent = rel.substringBeforeLast('/', "")
                val newDirs = ArrayList<String>()
                while (parent.isNotEmpty() && !Root.isDir("${install.gameDir}/$parent")) {
                    newDirs += parent; parent = parent.substringBeforeLast('/', "")
                }
                newDirs.asReversed().forEach { d -> if ((0 until dirs.length()).none { dirs.getString(it) == d }) dirs.put(d) }
                val old = Root.readBytes(target)
                if (old != null) {
                    File(backupDir, rel).apply { parentFile?.mkdirs() }.writeBytes(old)
                    replaced.put(rel)
                } else created.put(rel)
                val r = Root.writeBytes(target, bytes)
                if (!r.ok) fail("Could not write $target: ${r.err.trim()}")
                log("  installed $rel (${bytes.size / 1024} KB)")
            }
        } catch (e: Exception) {
            log("  ${t.name} stopped partway: ${e.message}. Revert removes what was installed.")
        }
        return record.put("backupDir", backupDir.absolutePath)
    }

    /** Changes numeric values the game already wrote to its Wine registry hive, keeping their type. */
    private fun wineReg(install: DroidDeck.Install, t: Tweak): JSONObject? {
        val path = iniPath(install, t.json.getString("path"))
        val text0 = path?.let { Root.readText(ctx, it) }
        if (path == null || text0 == null) {
            log("  ${t.name}: no Proton prefix yet. Launch the game once, quit, then apply again."); return null
        }
        val key = t.json.getString("key")
        val numbers = t.json.getJSONObject("numbers")
        var text: String = text0
        val prev = JSONObject()
        for (name in numbers.keys()) {
            val old = WineReg.getRaw(text, key, name)
            if (old == null) {
                log("  $name isn't set yet. Launch the game once, quit, then apply again."); continue
            }
            val rhs = WineReg.numberLike(old, numbers.getLong(name))
            val updated = rhs?.let { WineReg.setRaw(text, key, name, it) }
            if (updated == null) {
                log("  $name has an unexpected type ($old), left alone."); continue
            }
            prev.put(name, old)
            text = updated
            log("  registry $name: $old -> $rhs")
        }
        if (prev.length() == 0) return null
        write(path, text)
        return JSONObject().put("regPath", path).put("key", key).put("prev", prev)
    }

    fun revert(game: Game, pack: Pack) {
        if (!Root.available()) fail("Root access was denied.")
        if (appliedState(game, pack.kind) == null) fail("${pack.name} isn't applied for ${game.name}.")
        stopDroidDeck()
        backup(listOf(dd.envFile) + dd.localConfigs())
        revertInternal(game, pack.kind)
        log("Reverted ${pack.name} for ${game.name}.")
    }

    private fun revertInternal(game: Game, kind: String) {
        val state = appliedState(game, kind) ?: return
        val scope = game.steamAppId.toString()

        state.optJSONObject("tweaks")?.let { tweaks ->
            for (id in tweaks.keys().asSequence().toList().asReversed()) {
                val t = tweaks.getJSONObject(id)
                if (t.has("files")) {
                    val dir = t.getString("dir")
                    val files = t.getJSONArray("files")
                    for (i in 0 until files.length()) {
                        val n = files.getString(i)
                        if (Root.rename("$dir/$n$BAK", "$dir/$n").ok) log("  restored $n")
                    }
                } else if (t.has("modDir")) {
                    val dir = t.getString("modDir")
                    val backupDir = File(t.getString("backupDir"))
                    val created = t.getJSONArray("created")
                    for (i in 0 until created.length()) {
                        val rel = created.getString(i)
                        if (Root.deleteFile("$dir/$rel").ok) log("  removed $rel")
                    }
                    val replaced = t.getJSONArray("replaced")
                    for (i in 0 until replaced.length()) {
                        val rel = replaced.getString(i)
                        val saved = File(backupDir, rel)
                        if (saved.isFile && Root.writeBytes("$dir/$rel", saved.readBytes()).ok) log("  restored $rel")
                    }
                    val dirs = t.getJSONArray("dirs")
                    for (i in dirs.length() - 1 downTo 0) Root.removeEmptyDir("$dir/${dirs.getString(i)}")
                    backupDir.deleteRecursively()
                } else if (t.has("iniKeyPath")) {
                    val path = t.getString("iniKeyPath")
                    val text = Root.readText(path) ?: continue
                    Ini.setAnywhere(text, t.getString("key"), t.getString("old"))?.let { write(path, it) }
                    log("  restored ${t.getString("key")}")
                } else if (t.has("regPath")) {
                    val path = t.getString("regPath")
                    var text = Root.readText(ctx, path) ?: continue
                    val key = t.getString("key")
                    val prev = t.getJSONObject("prev")
                    for (name in prev.keys()) {
                        WineReg.setRaw(text, key, name, prev.getString(name))?.let { text = it }
                    }
                    write(path, text)
                    log("  restored registry values")
                } else if (t.has("path")) {
                    val path = t.getString("path")
                    var text = Root.readText(ctx, path) ?: continue
                    val prev = t.getJSONObject("prev")
                    for (sec in prev.keys()) {
                        val kv = prev.getJSONObject(sec)
                        for (k in kv.keys()) {
                            val p = kv.getJSONObject(k)
                            text = if (p.optBoolean("absent")) Ini.remove(text, sec, k) else Ini.set(text, sec, k, p.getString("value"))
                        }
                    }
                    write(path, text)
                    log("  restored ${path.substringAfterLast('/')}")
                }
            }
        }

        state.optJSONObject("launch")?.let { launch ->
            for (cfg in launch.keys()) {
                val text = Root.readText(ctx, cfg) ?: continue
                val p = launch.getJSONObject(cfg)
                val path = STEAM_APPS + scope
                val updated = if (p.optBoolean("absent")) Vdf.remove(text, path, "LaunchOptions")
                else Vdf.set(text, path, 4, "LaunchOptions", p.getString("value"))
                if (updated != null) write(cfg, updated)
                log("  restored launch options")
            }
        }

        state.optJSONObject("env")?.let { envPrev ->
            val text = Root.readText(ctx, dd.envFile)
            val env = EnvFile.parseOrNew(text)
            val prev = buildMap<String, EnvFile.Prev> { envPrev.keys().forEach { put(it, EnvFile.Prev.fromJson(envPrev.getJSONObject(it))) } }
            EnvFile.restore(env, scope, prev)
            write(dd.envFile, EnvFile.encode(env))
            log("  restored per-game environment")
        }
        stateFile(game, kind).delete()
    }

    /** Human-readable summary of what DroidDeck currently has for the game. */
    fun status(game: Game): String {
        val sb = StringBuilder()
        val scope = game.steamAppId.toString()
        val env = runCatching { EnvFile.parseOrNew(Root.readText(ctx, dd.envFile)) }.getOrNull()
        val entries = env?.let { EnvFile.gameEntries(it, scope) }
        if (entries == null || entries.length() == 0) sb.appendLine("Per-game environment: none (inherits Default profile)")
        else {
            sb.appendLine("Per-game environment (${entries.length()} entries):")
            entries.keys().forEach { k -> sb.appendLine("  $k = ${if (entries.isNull(k)) "(unset)" else entries.getString(k)}") }
        }
        val proton = runCatching { Root.readText(dd.protonGames) }.getOrNull().orEmpty()
        val choice = runCatching { JSONObject(proton).optJSONObject("games")?.optJSONObject(scope) }.getOrNull()
        sb.appendLine("Proton: " + (choice?.let { "${it.optString("dir")} (per-game)" } ?: "inherits Default profile"))
        for (cfg in dd.localConfigs()) {
            val text = Root.readText(ctx, cfg) ?: continue
            val lo = Vdf.get(text, STEAM_APPS + scope, "LaunchOptions")
            sb.appendLine("Launch options: ${if (lo.isNullOrEmpty()) "(none)" else lo}")
        }
        val install = dd.findInstall(game)
        sb.appendLine("Install: " + (install?.gameDir ?: "not found"))
        if (install != null) sb.appendLine("Proton prefix: " + (install.prefix ?: "not created yet (launch once)"))
        for (p in game.packs) {
            val label = p.short.replaceFirstChar { it.uppercase() }
            sb.appendLine("$label: " + if (p.manual != null) "in-game only" else if (appliedState(game, p.kind) != null) "APPLIED" else "not applied")
        }
        return sb.toString().trimEnd()
    }

    companion object {
        const val BAK = ".ddhbak"

        /** Adds the template's arguments to whatever the user already has, without duplicating any. */
        fun mergeLaunchOptions(existing: String?, template: String): String {
            val cur = existing?.trim().orEmpty()
            if (cur.isEmpty()) return template
            val extra = template.replace("%command%", " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val missing = extra.filter { tok ->
                !Regex("(^|\\s)" + Regex.escape(tok) + "(\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(cur)
            }
            if (missing.isEmpty()) return cur
            val base = if (cur.contains("%command%")) cur else "%command% $cur"
            return base + " " + missing.joinToString(" ")
        }
    }
}
