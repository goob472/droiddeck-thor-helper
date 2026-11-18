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

data class Game(
    val id: String,
    val name: String,
    val steamAppId: Long,
    val installDir: String,
    val summary: String,
    val notes: List<String>,
    val launchOptions: String?,
    val env: Map<String, String?>,
    val variants: List<Variant>,
    val tweaks: List<Tweak>,
)

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
            val variants = g.getJSONArray("variants")
            val tweaks = g.optJSONArray("tweaks") ?: JSONArray()
            Game(
                id = g.getString("id"),
                name = g.getString("name"),
                steamAppId = g.getLong("steamAppId"),
                installDir = g.getString("installDir"),
                summary = g.optString("summary"),
                notes = strList(g.optJSONArray("notes")),
                launchOptions = g.optString("launchOptions").ifEmpty { null },
                env = strMap(g.optJSONObject("env")),
                variants = (0 until variants.length()).map { j ->
                    val v = variants.getJSONObject(j)
                    Variant(v.getString("id"), v.getString("name"), v.optString("description"), strMap(v.optJSONObject("fex")))
                },
                tweaks = (0 until tweaks.length()).map { j ->
                    val t = tweaks.getJSONObject(j)
                    Tweak(t.getString("id"), t.getString("name"), t.optString("description"), t.getString("type"), t.optBoolean("default"), t)
                },
            )
        }
    }

    /** The full set of per-game environment entries for a variant. null = explicitly unset. */
    fun environment(game: Game, variant: Variant): LinkedHashMap<String, String?> {
        val out = LinkedHashMap<String, String?>()
        FEX_KEYS.forEach { out[it] = null }
        out.putAll(variant.fex)
        out.putAll(game.env)
        return out
    }
}
