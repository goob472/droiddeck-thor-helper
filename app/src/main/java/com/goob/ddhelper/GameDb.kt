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
) {
    companion object {
        const val SETTINGS = "settings"
        const val UI = "ui"
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
) {
    val settings: Pack
        get() = Pack(Pack.SETTINGS, "Best settings", profile.description, GameDb.environment(this), launchOptions, tweaks)
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
                ui = ui?.let {
                    Pack(
                        Pack.UI, it.getString("name"), it.optString("description"), strMap(it.optJSONObject("env")),
                        it.optString("launchOptions").ifEmpty { null }, tweaks(it.optJSONArray("tweaks") ?: JSONArray()),
                        it.optString("manual").ifEmpty { null },
                    )
                },
            )
        }
    }

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
