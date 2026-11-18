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

    private fun stateFile(game: Game) = File(File(ctx.filesDir, "applied").apply { mkdirs() }, "${game.id}.json")

    fun appliedState(game: Game): JSONObject? =
        stateFile(game).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    private fun fail(msg: String): Nothing = throw IllegalStateException(msg)

    private fun write(path: String, text: String) {
        val r = Root.writeText(ctx, path, text)
        if (!r.ok) fail("Could not write $path: ${r.err.trim()}")
    }

    fun stopDroidDeck() {
        log("Stopping ${dd.pkg} so it cannot overwrite the files…")
        Root.run("am force-stop ${Root.q(dd.pkg)}")
        Thread.sleep(800)
    }

    private fun backup(paths: List<String>) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(ctx.filesDir, "backups/$stamp")
        for (p in paths) {
            if (!Root.exists(p)) continue
            val dest = File(dir, p.trimStart('/').replace('/', '_'))
            if (Root.copy(p, dest.absolutePath).ok) log("Backed up $p")
        }
        log("Backups: ${dir.absolutePath}")
    }

    fun apply(game: Game) {
        if (!Root.available()) fail("Root access was denied.")
        stopDroidDeck()
        backup(listOf(dd.envFile) + dd.localConfigs())
        if (appliedState(game) != null) {
            log("A profile is already applied. Reverting it first so the new one starts from your own settings.")
            revertInternal(game)
        }
        val state = JSONObject()
            .put("game", game.id).put("profile", game.profile.name).put("pkg", dd.pkg)
            .put("appliedAt", System.currentTimeMillis())
        val scope = game.steamAppId.toString()

        // 1. Per-game environment (FEX preset + fixes).
        val envText = Root.readText(ctx, dd.envFile)
        if (envText == null) log("No game-environment.json yet; creating one.")
        val env = EnvFile.parseOrNew(envText)
        val entries = GameDb.environment(game)
        val prev = EnvFile.apply(env, scope, entries)
        write(dd.envFile, EnvFile.encode(env))
        state.put("env", JSONObject().apply { prev.forEach { (k, p) -> put(k, p.toJson()) } })
        entries.forEach { (k, v) -> log("  env $k = ${v ?: "(unset)"}") }
        log("Wrote per-game environment for app $scope.")
        // Save progress now so a failure later can still be reverted.
        stateFile(game).writeText(state.toString(2))

        // 2. Steam launch options.
        if (game.launchOptions != null) {
            val configs = dd.localConfigs()
            if (configs.isEmpty()) log("No Steam localconfig.vdf found. Sign in to Steam in DroidDeck once, then apply again to set launch options.")
            val launch = JSONObject()
            for (cfg in configs) {
                val text = Root.readText(ctx, cfg) ?: continue
                val path = STEAM_APPS + scope
                val old = Vdf.get(text, path, "LaunchOptions")
                val merged = mergeLaunchOptions(old, game.launchOptions)
                val updated = Vdf.set(text, path, 4, "LaunchOptions", merged)
                if (updated == null) {
                    log("Skipped $cfg (unexpected layout)."); continue
                }
                write(cfg, updated)
                launch.put(cfg, if (old == null) JSONObject().put("absent", true) else JSONObject().put("value", old))
                log("Launch options: \"$merged\" (${cfg.substringAfter("/userdata/").substringBefore('/')})")
            }
            state.put("launch", launch)
            stateFile(game).writeText(state.toString(2))
        }

        // 3. Game-file tweaks.
        val install = dd.findInstall(game.steamAppId, game.installDir)
        if (install == null) log("${game.name} is not installed in any Steam library DroidDeck uses. Skipping game-file tweaks.")
        else log("Found ${game.name} at ${install.gameDir}")
        val tweaksState = JSONObject()
        for (t in game.tweaks) {
            if (install == null) break
            try {
                when (t.type) {
                    "renameFiles" -> tweaksState.put(t.id, renameFiles(install, t))
                    "iniMerge" -> iniMerge(install, t)?.let { tweaksState.put(t.id, it) }
                    else -> log("Unknown tweak type ${t.type}")
                }
            } catch (e: Exception) {
                log("Tweak '${t.name}' failed: ${e.message}")
            }
            state.put("tweaks", tweaksState)
            stateFile(game).writeText(state.toString(2))
        }
        state.put("tweaks", tweaksState)
        if (install != null) state.put("install", install.gameDir).put("prefix", install.prefix ?: JSONObject.NULL)
        stateFile(game).writeText(state.toString(2))
        log("Done. Open DroidDeck and launch ${game.name}. The changes apply from the next game launch.")
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
        var text = Root.readText(ctx, path) ?: ""
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

    fun revert(game: Game) {
        if (!Root.available()) fail("Root access was denied.")
        if (appliedState(game) == null) fail("Nothing has been applied for ${game.name}.")
        stopDroidDeck()
        backup(listOf(dd.envFile) + dd.localConfigs())
        revertInternal(game)
        log("Reverted ${game.name} to your previous settings.")
    }

    private fun revertInternal(game: Game) {
        val state = appliedState(game) ?: return
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
        stateFile(game).delete()
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
        val proton = Root.run("cat ${Root.q(dd.protonGames)} 2>/dev/null").out
        val choice = runCatching { JSONObject(proton).optJSONObject("games")?.optJSONObject(scope) }.getOrNull()
        sb.appendLine("Proton: " + (choice?.let { "${it.optString("dir")} (per-game)" } ?: "inherits Default profile"))
        for (cfg in dd.localConfigs()) {
            val text = Root.readText(ctx, cfg) ?: continue
            val lo = Vdf.get(text, STEAM_APPS + scope, "LaunchOptions")
            sb.appendLine("Launch options: ${if (lo.isNullOrEmpty()) "(none)" else lo}")
        }
        val install = dd.findInstall(game.steamAppId, game.installDir)
        sb.appendLine("Install: " + (install?.gameDir ?: "not found"))
        if (install != null) sb.appendLine("Proton prefix: " + (install.prefix ?: "not created yet (launch once)"))
        sb.appendLine("Helper profile: " + if (appliedState(game) != null) "APPLIED" else "not applied")
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
