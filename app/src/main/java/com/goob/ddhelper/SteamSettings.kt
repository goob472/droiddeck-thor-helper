package com.goob.ddhelper

import org.json.JSONArray
import org.json.JSONObject

/** Text edits for Steam's per-game controller settings and DroidDeck's per-game Proton choice. */
object SteamSettings {
    const val INPUT_KEY = "UseSteamControllerConfig"
    /** Where current Steam keeps the per-game Steam Input override, and the older place it also reads. */
    val ROOT_APPS = listOf("UserLocalConfigStore", "apps")
    val OLD_APPS = listOf("UserLocalConfigStore", "Software", "Valve", "Steam", "apps")
    /** The keys that say which controller layout a game uses. */
    val LAYOUT_KEYS = listOf("template", "workshop", "autosave")
    const val EMPTY_CONFIGSET = "\"controller_config\"\n{\n}\n"

    private fun JSONObject.prev(key: String, value: String?) = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)

    /** Edited text, plus what to hand back to the matching restore function. */
    data class Edit(val text: String, val prev: JSONObject)

    /** Steam Input for one game: "2" forces it on, "0" off. */
    fun setSteamInput(text: String, app: String, value: String): Edit? {
        val prev = JSONObject()
            .put("hadApps", Vdf.hasSection(text, ROOT_APPS))
            .put("hadRoot", Vdf.hasSection(text, ROOT_APPS + app))
            .prev("root", Vdf.get(text, ROOT_APPS + app, INPUT_KEY))
            .prev("old", Vdf.get(text, OLD_APPS + app, INPUT_KEY))
        val out = Vdf.set(text, ROOT_APPS + app, 1, INPUT_KEY, value) ?: return null
        return Edit(Vdf.remove(out, OLD_APPS + app, INPUT_KEY), prev)
    }

    fun restoreSteamInput(text: String, app: String, prev: JSONObject): String {
        var out = put(text, ROOT_APPS + app, 1, INPUT_KEY, prev.text("root"))
        if (!prev.getBoolean("hadRoot")) out = Vdf.removeEmptySection(out, ROOT_APPS + app)
        if (!prev.getBoolean("hadApps")) out = Vdf.removeEmptySection(out, ROOT_APPS)
        return prev.text("old")?.let { put(out, OLD_APPS + app, 4, INPUT_KEY, it) } ?: out
    }

    /** Selects one of Steam's stock layouts for a game in a configset_controller_*.vdf. */
    fun setLayout(text: String, app: String, template: String): Edit? {
        val path = listOf("controller_config", app)
        val prev = JSONObject().put("had", Vdf.hasSection(text, path))
        LAYOUT_KEYS.forEach { prev.prev(it, Vdf.get(text, path, it)) }
        var out = Vdf.set(text, path, 1, "template", template) ?: return null
        (LAYOUT_KEYS - "template").forEach { out = Vdf.remove(out, path, it) }
        return Edit(out, prev)
    }

    fun restoreLayout(text: String, app: String, prev: JSONObject): String {
        val path = listOf("controller_config", app)
        var out = text
        LAYOUT_KEYS.forEach { out = put(out, path, 1, it, prev.text(it)) }
        return if (prev.getBoolean("had")) out else Vdf.removeEmptySection(out, path)
    }

    private fun put(text: String, path: List<String>, mustExist: Int, key: String, value: String?) =
        if (value == null) Vdf.remove(text, path, key) else Vdf.set(text, path, mustExist, key, value) ?: text

    /** DroidDeck's default Proton from droiddeck-compat/state.json, as a games.json entry. */
    fun defaultProton(state: String?): JSONObject? {
        val o = runCatching { JSONObject(state ?: "") }.getOrNull() ?: return null
        val tools = o.optJSONArray("tools") ?: JSONArray()
        val name = o.optString("default")
        return (0 until tools.length()).mapNotNull { tools.optJSONObject(it) }
            .firstOrNull { it.optString("name") == name && it.optString("dir").isNotEmpty() }
            ?.let { JSONObject().put("valve", it.optBoolean("valve")).put("dir", it.getString("dir")) }
    }

    /** games.json in DroidDeck's version 1 format; an unreadable or newer file is refused, not replaced. */
    fun parseGames(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return JSONObject().put("version", 1).put("games", JSONObject())
        return runCatching { JSONObject(text) }.getOrNull()
            ?.takeIf { it.optInt("version") == 1 && it.optJSONObject("games") != null }
    }
}
