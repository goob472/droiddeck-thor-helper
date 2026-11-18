package com.goob.ddhelper

import org.json.JSONArray
import org.json.JSONObject

data class Variant(val id: String, val name: String, val description: String, val fex: Map<String, String?>)

data class Tweak(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val default: Boolean,
    val json: JSONObject,
)

/** A set of changes applied and reverted together: the game's best settings, or its UI mod. */
data class Pack(
    val kind: String,
    val name: String,
    val description: String,
    val env: Map<String, String?>,
    val launchOptions: String?,
    val tweaks: List<Tweak>,
    /** When set, nothing can be changed from outside the game; this explains what to do in-game. */
    val manual: String? = null,
    /** Short label for the button: "Apply <short>" / "Revert <short>". */
    val short: String = name,
) {
    /**
     * Identifies exactly what this pack changes. Recorded when applied, so the app can tell when an
     * update brings a newer profile and offer to re-apply it.
     */
    fun fingerprint(): String {
        val sb = StringBuilder()
        env.toSortedMap().forEach { (k, v) -> sb.append(k).append('=').append(v ?: "\u0000").append('\n') }
        sb.append("launch=").append(launchOptions ?: "").append('\n')
        tweaks.forEach { sb.append(it.json.toString()).append('\n') }
        return java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SETTINGS = "settings"
        const val UI = "ui"
        private val KIND = Regex("[a-z][a-z0-9_]{0,23}")
        fun validKind(kind: String) = KIND.matches(kind)
    }
}

data class Game(
    val id: String,
    val name: String,
    val steamAppId: Long,
    val installDir: String,
    val summary: String,
    val notes: List<String>,
    val launchOptions: String?,
    val env: Map<String, String?>,
    val profile: Variant,
    val tweaks: List<Tweak>,
    val ui: Pack? = null,
    /** Files inside the install folder that prove the game is really installed (any one is enough). */
    val detectFiles: List<String> = emptyList(),
    /** Further optional packs, each with its own button (for example a controller fix). */
    val extras: List<Pack> = emptyList(),
) {
    val settings: Pack
        get() = Pack(Pack.SETTINGS, "Best settings", profile.description, GameDb.environment(this), launchOptions, tweaks, short = "best settings")

    /** Every pack in button order: settings, UI mod, then extras. */
    val packs: List<Pack> get() = listOf(settings) + listOfNotNull(ui) + extras
}

object GameDb {
    /** Every key any DroidDeck FEX preset touches (core/FexPreset.kt). Keys a variant leaves out are unset explicitly. */
    val FEX_KEYS = listOf(
        "FEX_TSOENABLED", "FEX_VECTORTSOENABLED", "FEX_MEMCPYSETTSOENABLED", "FEX_HALFBARRIERTSOENABLED",
        "FEX_X87REDUCEDPRECISION", "FEX_MULTIBLOCK", "FEX_SMCCHECKS", "FEX_DISABLEL2CACHE", "FEX_DYNAMICL1CACHE",
        "FEX_DYNAMICL1CACHEINCREASECOUNTHEURISTIC", "FEX_DYNAMICL1CACHEDECREASECOUNTHEURISTIC",
        "FEX_SMALLTSCSCALE", "FEX_VOLATILEMETADATA", "FEX_HIDEHYPERVISORBIT",
    )

    private fun strMap(o: JSONObject?): Map<String, String?> = buildMap {
        o?.keys()?.forEach { k -> put(k, if (o.isNull(k)) null else o.getString(k)) }
    }

    private fun strList(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    fun parse(text: String): List<Game> {
        val root = JSONObject(text)
        val games = root.getJSONArray("games")
        return (0 until games.length()).map { i ->
            val g = games.getJSONObject(i)
            val profile = g.getJSONObject("profile")
            val tweaks = g.optJSONArray("tweaks") ?: JSONArray()
            val ui = g.optJSONObject("ui")
            Game(
                id = g.getString("id"),
                name = g.getString("name"),
                steamAppId = g.getLong("steamAppId"),
                installDir = g.getString("installDir"),
                summary = g.optString("summary"),
                notes = strList(g.optJSONArray("notes")),
                launchOptions = g.optString("launchOptions").ifEmpty { null },
                env = strMap(g.optJSONObject("env")),
                profile = Variant("best", profile.getString("name"), profile.optString("description"), strMap(profile.optJSONObject("fex"))),
                tweaks = tweaks(tweaks),
                detectFiles = strList(g.optJSONArray("detectFiles")),
                ui = ui?.let { pack(Pack.UI, it, "UI mod") },
                extras = (g.optJSONArray("extras") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { j ->
                        val p = a.getJSONObject(j)
                        val kind = p.getString("kind")
                        require(Pack.validKind(kind) && kind != Pack.SETTINGS && kind != Pack.UI) { "Bad pack kind $kind" }
                        pack(kind, p, p.optString("short").ifEmpty { kind })
                    }
                },
            )
        }
    }

    private fun pack(kind: String, p: JSONObject, short: String) = Pack(
        kind, p.getString("name"), p.optString("description"), strMap(p.optJSONObject("env")),
        p.optString("launchOptions").ifEmpty { null }, tweaks(p.optJSONArray("tweaks") ?: JSONArray()),
        p.optString("manual").ifEmpty { null }, p.optString("short").ifEmpty { short },
    )

    private fun tweaks(a: JSONArray): List<Tweak> = (0 until a.length()).map { j ->
        val t = a.getJSONObject(j)
        Tweak(t.getString("id"), t.getString("name"), t.optString("description"), t.getString("type"), t.optBoolean("default"), t)
    }

    /** The full set of per-game environment entries for the game's profile. null = explicitly unset. */
    fun environment(game: Game): LinkedHashMap<String, String?> {
        val out = LinkedHashMap<String, String?>()
        FEX_KEYS.forEach { out[it] = null }
        out.putAll(game.profile.fex)
        out.putAll(game.env)
        return out
    }
}
