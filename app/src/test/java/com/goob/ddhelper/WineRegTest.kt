package com.goob.ddhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WineRegTest {
    private val key = "Software\\IO Interactive\\HITMAN3"
    private val reg = "WINE REGISTRY Version 2\n;; All keys relative to \\\\User\\\\S-1-5-21\n\n" +
        "[Software\\\\IO Interactive\\\\HITMAN3] 1700000000\n#time=1da\n" +
        "\"SharpeningStrength\"=dword:00000002\n\"SuperSampling\"=\"1000\"\n\n" +
        "[Software\\\\Wine] 1700000000\n\"SuperSampling\"=\"1\"\n"

    @Test fun readsAndWritesKeepingType() {
        assertEquals("\"1000\"", WineReg.getRaw(reg, key, "SuperSampling"))
        val s = WineReg.setRaw(reg, key, "SuperSampling", WineReg.numberLike("\"1000\"", 750)!!)!!
        assertEquals("\"750\"", WineReg.getRaw(s, key, "SuperSampling"))
        // The same name in another key is untouched.
        assertEquals("\"1\"", WineReg.getRaw(s, "Software\\Wine", "SuperSampling"))
        assertEquals("dword:000002ee", WineReg.numberLike("dword:00000002", 750))
        val back = WineReg.setRaw(s, key, "SuperSampling", "\"1000\"")!!
        assertEquals(reg, back)
    }

    @Test fun missingValuesAreNotCreated() {
        assertNull(WineReg.getRaw(reg, key, "Nope"))
        assertNull(WineReg.setRaw(reg, key, "Nope", "\"1\""))
        assertNull(WineReg.setRaw(reg, "Software\\Missing", "SuperSampling", "\"1\""))
        assertNull(WineReg.numberLike("hex:01,02", 1))
    }

    @Test fun crlfPreserved() {
        val crlf = reg.replace("\n", "\r\n")
        val s = WineReg.setRaw(crlf, key, "SuperSampling", "\"750\"")!!
        assertTrue(s.contains("\"SuperSampling\"=\"750\"\r\n"))
    }

    @Test fun allGamesParse() {
        val games = GameDb.parse(File("src/main/assets/games.json").readText())
        assertEquals(listOf("maneater", "mhw", "hitman_woa", "dr2", "dr2otr", "disgaea2"), games.map { it.id })
        val mhw = games.first { it.id == "mhw" }
        assertEquals(582010L, mhw.steamAppId)
        assertNull(mhw.launchOptions)
        val mhwEnv = GameDb.environment(mhw)
        assertEquals("1", mhwEnv["FEX_TSOENABLED"]); assertNull(mhwEnv["FEX_SMCCHECKS"])
        val ini = mhw.tweaks.single().json.getJSONObject("sections").getJSONObject("GraphicsOption")
        assertEquals("Off", ini.getString("DirectX12Enable"))
        val hitman = games.first { it.id == "hitman_woa" }
        assertEquals(1659040L, hitman.steamAppId)
        assertEquals("%command% -skip_launcher", hitman.launchOptions)
        assertEquals("nodxr", GameDb.environment(hitman)["VKD3D_CONFIG"])
        assertEquals(key, hitman.tweaks.single().json.getString("key"))
    }

    @Test fun uiPacksParseAndStaySeparateFromSettings() {
        val games = GameDb.parse(File("src/main/assets/games.json").readText()).associateBy { it.id }
        val maneaterUi = games.getValue("maneater").ui!!
        assertEquals(Pack.UI, maneaterUi.kind)
        assertTrue(maneaterUi.env.isEmpty())
        val sec = maneaterUi.tweaks.single().json.getJSONObject("sections").getJSONObject("/Script/Engine.UserInterfaceSettings")
        assertEquals("1.25", sec.getString("ApplicationScale"))
        assertTrue(games.getValue("mhw").ui!!.manual!!.contains("Text Size"))
        val hitmanUi = games.getValue("hitman_woa").ui!!
        assertEquals(mapOf("SteamDeck" to "1"), hitmanUi.env)
        // The UI pack must never touch a key the settings pack owns, or reverting one would undo the other.
        for (g in games.values) {
            val ui = g.ui ?: continue
            assertTrue(g.id, (ui.env.keys intersect g.settings.env.keys).isEmpty())
            assertNull(ui.launchOptions)
            assertEquals(Pack.SETTINGS, g.settings.kind)
        }
    }
}
