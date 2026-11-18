package com.goob.ddhelper

import org.json.JSONObject

/**
 * DroidDeck's per-game environment store: files/game-environment.json, format
 * {"version":1,"shared":{NAME:value|null},"games":{"<appid>":{NAME:value|null}}}
 * (session/GameEnvironmentStore.kt). JSON null means "explicitly unset for this game".
 * DroidDeck re-publishes it into the guest at every session start.
 */
object EnvFile {
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Value previously stored for a key: absent, explicit null, or a string. */
    sealed class Prev {
        object Absent : Prev()
        object Null : Prev()
        data class Value(val v: String) : Prev()

        fun toJson(): JSONObject = when (this) {
            Absent -> JSONObject().put("absent", true)
            Null -> JSONObject().put("null", true)
            is Value -> JSONObject().put("value", v)
        }

        companion object {
            fun fromJson(o: JSONObject): Prev = when {
                o.optBoolean("absent") -> Absent
                o.optBoolean("null") -> Null
                else -> Value(o.getString("value"))
            }
        }
    }

    fun parseOrNew(text: String?): JSONObject {
        if (text.isNullOrBlank()) return JSONObject().put("version", 1).put("shared", JSONObject()).put("games", JSONObject())
        val o = JSONObject(text)
        require(o.optInt("version") == 1) { "Unsupported game-environment.json version ${o.opt("version")}" }
        if (!o.has("shared")) o.put("shared", JSONObject())
        if (!o.has("games")) o.put("games", JSONObject())
        return o
    }

    fun gameEntries(root: JSONObject, scope: String): JSONObject? = root.getJSONObject("games").optJSONObject(scope)

    /** Applies [entries] for [scope]; returns what each key held before. */
    fun apply(root: JSONObject, scope: String, entries: Map<String, String?>): Map<String, Prev> {
        require(scope.matches(Regex("[1-9][0-9]*")))
        val games = root.getJSONObject("games")
        val game = games.optJSONObject(scope) ?: JSONObject().also { games.put(scope, it) }
        val prev = LinkedHashMap<String, Prev>()
        for ((k, v) in entries) {
            require(NAME.matches(k)) { "Bad variable name $k" }
            require(v == null || (v.length <= 8192 && '\u0000' !in v)) { "Bad value for $k" }
            prev[k] = when {
                !game.has(k) -> Prev.Absent
                game.isNull(k) -> Prev.Null
                else -> Prev.Value(game.getString(k))
            }
            game.put(k, v ?: JSONObject.NULL)
        }
        return prev
    }

    /** Puts every key back the way [prev] recorded it. */
    fun restore(root: JSONObject, scope: String, prev: Map<String, Prev>) {
        val games = root.getJSONObject("games")
        val game = games.optJSONObject(scope) ?: JSONObject().also { games.put(scope, it) }
        for ((k, p) in prev) when (p) {
            Prev.Absent -> game.remove(k)
            Prev.Null -> game.put(k, JSONObject.NULL)
            is Prev.Value -> game.put(k, p.v)
        }
        if (game.length() == 0) games.remove(scope)
    }

    fun encode(root: JSONObject): String = root.toString()
}
