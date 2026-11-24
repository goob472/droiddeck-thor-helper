package com.goob.ddhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SteamSettingsTest {
    private val app = "495280"
    private fun cfg(old: String = "", rootApps: String = "\t\"apps\"\n\t{\n\t}\n") =
        "\"UserLocalConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n\t\t\t\t\"apps\"\n\t\t\t\t{\n" +
            old + "\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n" + rootApps + "}\n"

    @Test fun steamInputForcedOnAndRestored() {
        val off = "\t\"apps\"\n\t{\n\t\t\"495280\"\n\t\t{\n\t\t\t\"UseSteamControllerConfig\"\t\t\"0\"\n\t\t}\n\t}\n"
        for (text in listOf(cfg(), cfg(rootApps = off), cfg(rootApps = ""))) {
            val e = SteamSettings.setSteamInput(text, app, "2")!!
            assertEquals("2", Vdf.get(e.text, SteamSettings.ROOT_APPS + app, SteamSettings.INPUT_KEY))
            assertEquals(text, SteamSettings.restoreSteamInput(e.text, app, e.prev))
        }
        val legacy = cfg("\t\t\t\t\t\"495280\"\n\t\t\t\t\t{\n\t\t\t\t\t\t\"UseSteamControllerConfig\"\t\t\"0\"\n\t\t\t\t\t}\n")
        val e = SteamSettings.setSteamInput(legacy, app, "2")!!
        assertNull(Vdf.get(e.text, SteamSettings.OLD_APPS + app, SteamSettings.INPUT_KEY))
        assertEquals(legacy, SteamSettings.restoreSteamInput(e.text, app, e.prev))
    }

    @Test fun layoutReplacesSavedChoiceAndRestores() {
        val empty = SteamSettings.EMPTY_CONFIGSET
        val e = SteamSettings.setLayout(empty, app, "pad.vdf")!!
        assertEquals("pad.vdf", Vdf.get(e.text, listOf("controller_config", app), "template"))
        assertEquals(empty, SteamSettings.restoreLayout(e.text, app, e.prev))
        val saved = "\"controller_config\"\n{\n\t\"495280\"\n\t{\n\t\t\"autosave\"\t\t\"1\"\n\t}\n\t\"570\"\n\t{\n\t\t\"workshop\"\t\t\"5\"\n\t}\n}\n"
        val f = SteamSettings.setLayout(saved, app, "pad.vdf")!!
        assertNull(Vdf.get(f.text, listOf("controller_config", app), "autosave"))
        assertEquals("5", Vdf.get(f.text, listOf("controller_config", "570"), "workshop"))
        assertEquals(saved, SteamSettings.restoreLayout(f.text, app, f.prev))
    }

    @Test fun protonChoiceComesFromDroidDeckDefault() {
        val p = SteamSettings.defaultProton(
            "{\"default\":\"dd\",\"tools\":[{\"name\":\"x\",\"dir\":\"X\",\"valve\":true},{\"name\":\"dd\",\"dir\":\"DD 11\",\"valve\":false}]}",
        )!!
        assertEquals("DD 11", p.getString("dir")); assertEquals(false, p.getBoolean("valve"))
        assertNull(SteamSettings.defaultProton(null))
        assertNull(SteamSettings.defaultProton("{\"default\":\"gone\",\"tools\":[]}"))
        assertNull(SteamSettings.parseGames("{\"version\":2,\"games\":{}}"))
    }

    @Test fun disgaea2UsesNativeControllerNotKeys() {
        val g = GameDb.parse(File("src/main/assets/games.json").readText()).first { it.id == "disgaea2" }
        assertEquals(495280L, g.steamAppId)
        assertEquals(listOf("forceProton", "steamInput", "resetFile", "controllerLayout"), g.tweaks.map { it.type })
        assertEquals("2", g.tweaks[1].json.getString("value"))
        assertEquals("game.cfg", g.tweaks[2].json.getString("file"))
        assertEquals("a=1", Applier.preview("a=1".toByteArray()))
        assertEquals("00 ff", Applier.preview(byteArrayOf(0, -1)))
    }
}
