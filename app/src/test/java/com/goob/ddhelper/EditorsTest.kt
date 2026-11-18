package com.goob.ddhelper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EditorsTest {
    private val path = listOf("UserLocalConfigStore", "Software", "Valve", "Steam", "apps", "629820")

    private val vdf = "\"UserLocalConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n" +
        "\t\t\t\t\"apps\"\n\t\t\t\t{\n\t\t\t\t\t\"570\"\n\t\t\t\t\t{\n\t\t\t\t\t\t\"LaunchOptions\"\t\t\"-novid\"\n\t\t\t\t\t}\n\t\t\t\t}\n" +
        "\t\t\t}\n\t\t}\n\t}\n}\n"

    @Test fun vdfCreatesAppSectionAndValue() {
        val out = Vdf.set(vdf, path, 4, "LaunchOptions", "%command% -USEALLAVAILABLECORES")!!
        assertEquals("%command% -USEALLAVAILABLECORES", Vdf.get(out, path, "LaunchOptions"))
        assertEquals("-novid", Vdf.get(out, path.dropLast(1) + "570", "LaunchOptions"))
        // Re-set replaces in place.
        val again = Vdf.set(out, path, 4, "LaunchOptions", "x \"q\"")!!
        assertEquals("x \"q\"", Vdf.get(again, path, "LaunchOptions"))
        val removed = Vdf.remove(again, path, "LaunchOptions")
        assertNull(Vdf.get(removed, path, "LaunchOptions"))
        assertEquals("-novid", Vdf.get(removed, path.dropLast(1) + "570", "LaunchOptions"))
    }

    @Test fun vdfCreatesAppsSectionWhenMissing() {
        val noApps = "\"UserLocalConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n\t\t\t\t\"x\"\t\t\"1\"\n\t\t\t}\n\t\t}\n\t}\n}\n"
        val out = Vdf.set(noApps, path, 4, "LaunchOptions", "a")!!
        assertEquals("a", Vdf.get(out, path, "LaunchOptions"))
        assertEquals("1", Vdf.get(out, path.take(4), "x"))
    }

    @Test fun vdfRefusesUnexpectedLayout() {
        assertNull(Vdf.set("\"Other\"\n{\n}\n", path, 4, "LaunchOptions", "a"))
    }

    @Test fun vdfLibraryPaths() {
        val lib = "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"/root/.local/share/Steam\"\n\t}\n\t\"1\"\n\t{\n\t\t\"path\"\t\t\"/root/Storage/SteamLibrary\"\n\t}\n}\n"
        assertEquals(listOf("/root/.local/share/Steam", "/root/Storage/SteamLibrary"), Vdf.allValues(lib, "path"))
    }

    @Test fun iniSetGetRemove() {
        var t = ""
        t = Ini.set(t, "SystemSettings", "r.MotionBlurQuality", "0")
        t = Ini.set(t, "SystemSettings", "r.DepthOfFieldQuality", "0")
        t = Ini.set(t, "/Script/Engine.StreamingSettings", "s.AsyncLoadingThreadEnabled", "False")
        assertEquals("0", Ini.get(t, "SystemSettings", "r.MotionBlurQuality"))
        assertEquals("False", Ini.get(t, "/Script/Engine.StreamingSettings", "s.AsyncLoadingThreadEnabled"))
        t = Ini.set(t, "SystemSettings", "r.MotionBlurQuality", "2")
        assertEquals("2", Ini.get(t, "SystemSettings", "r.MotionBlurQuality"))
        t = Ini.remove(t, "/Script/Engine.StreamingSettings", "s.AsyncLoadingThreadEnabled")
        assertFalse(t.contains("StreamingSettings"))
        t = Ini.remove(t, "SystemSettings", "r.MotionBlurQuality")
        t = Ini.remove(t, "SystemSettings", "r.DepthOfFieldQuality")
        assertTrue(t.isBlank())
    }

    @Test fun iniKeepsOtherContent() {
        val orig = "[Core.System]\r\nPaths=../../Engine\r\n\r\n[SystemSettings]\r\nr.Foo=1\r\n"
        val t = Ini.set(orig, "SystemSettings", "r.Bar", "2")
        assertTrue(t.contains("Paths=../../Engine"))
        assertTrue(t.contains("r.Foo=1\r\nr.Bar=2"))
        assertEquals(orig, Ini.remove(t, "SystemSettings", "r.Bar"))
    }

    @Test fun envApplyAndRestoreRoundTrip() {
        val original = "{\"version\":1,\"shared\":{\"A\":\"1\"},\"games\":{\"629820\":{\"FEX_MULTIBLOCK\":\"0\",\"KEEP\":\"x\",\"FEX_SMCCHECKS\":null}}}"
        val root = EnvFile.parseOrNew(original)
        val game = GameDb.parse(File("src/main/assets/games.json").readText()).first()
        val entries = GameDb.environment(game)
        val prev = EnvFile.apply(root, "629820", entries)
        val g = root.getJSONObject("games").getJSONObject("629820")
        assertEquals("full", g.getString("FEX_SMCCHECKS"))
        assertEquals(":~0x20000000", g.getString("OPENSSL_ia32cap"))
        assertTrue(g.isNull("FEX_DISABLEL2CACHE"))
        assertEquals("x", g.getString("KEEP"))
        EnvFile.restore(root, "629820", prev)
        assertEquals(JSONObject(original).toString(), JSONObject(EnvFile.encode(root)).toString())
    }

    @Test fun envRestoreDropsEmptyGame() {
        val root = EnvFile.parseOrNew(null)
        val prev = EnvFile.apply(root, "629820", mapOf("X" to "1"))
        EnvFile.restore(root, "629820", prev)
        assertFalse(root.getJSONObject("games").has("629820"))
    }

    @Test fun launchOptionMerge() {
        val tpl = "%command% -USEALLAVAILABLECORES"
        assertEquals(tpl, Applier.mergeLaunchOptions(null, tpl))
        assertEquals(tpl, Applier.mergeLaunchOptions("", tpl))
        assertEquals("FOO=1 %command% -x -USEALLAVAILABLECORES", Applier.mergeLaunchOptions("FOO=1 %command% -x", tpl))
        assertEquals("%command% -useallavailablecores", Applier.mergeLaunchOptions("%command% -useallavailablecores", tpl))
        assertEquals("%command% -windowed -USEALLAVAILABLECORES", Applier.mergeLaunchOptions("-windowed", tpl))
    }

    @Test fun gamesJsonParses() {
        val games = GameDb.parse(File("src/main/assets/games.json").readText())
        val m = games.first { it.id == "maneater" }
        assertEquals(629820L, m.steamAppId)
        val env = GameDb.environment(m)
        assertEquals("full", env["FEX_SMCCHECKS"]); assertEquals("1", env["FEX_HIDEHYPERVISORBIT"])
        assertEquals("1", env["FEX_TSOENABLED"]); assertEquals("1", env["FEX_MULTIBLOCK"])
        GameDb.FEX_KEYS.forEach { assertTrue(env.containsKey(it)) }
        assertEquals(listOf("skip_intro", "engine_perf", "no_async_loading"), m.tweaks.map { it.id })
        assertNotNull(m.tweaks.firstOrNull { it.id == "skip_intro" })
    }
}
