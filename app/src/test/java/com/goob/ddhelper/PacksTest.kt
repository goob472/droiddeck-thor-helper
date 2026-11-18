package com.goob.ddhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PacksTest {
    private val games = GameDb.parse(File("src/main/assets/games.json").readText()).associateBy { it.id }

    @Test fun iniKeyKeepsSpacingAndOnlyEditsExistingKeys() {
        val text = "[Render]\r\nWidth = 1280\r\nUseMultiCore = true\r\nVSync=1\r\n"
        assertEquals("true", Ini.getAnywhere(text, "UseMultiCore"))
        val out = Ini.setAnywhere(text, "UseMultiCore", "false")!!
        assertEquals("[Render]\r\nWidth = 1280\r\nUseMultiCore = false\r\nVSync=1\r\n", out)
        // Reverting with the recorded old value gives back the original bytes.
        assertEquals(text, Ini.setAnywhere(out, "UseMultiCore", "true"))
        assertNull(Ini.setAnywhere(text, "NotThere", "1"))
        // Section-less files work too.
        assertEquals("UseMultiCore = false\n", Ini.setAnywhere("UseMultiCore = true\n", "usemulticore", "false"))
        // A sectioned set keeps "key = value" style as well.
        assertEquals("[A]\nk = 2\n", Ini.set("[A]\nk = 1\n", "A", "k", "2"))
    }

    @Test fun zipEntriesCannotEscapeTheGameFolder() {
        listOf("../x.dll", "a/../../x", "/abs.dll", "C:/x.dll", "a//b", "./x", "dir/").forEach {
            assertNull(it, ModDownload.safeEntryName(it))
        }
        assertEquals("data/shaders/x.big", ModDownload.safeEntryName("data\\shaders\\x.big"))
        val zip = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("data/")); z.closeEntry()
                z.putNextEntry(ZipEntry("data/ok.txt")); z.write("hi".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertEquals(listOf("data/ok.txt"), ModDownload.unzip(zip).map { it.first })
        val evil = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("../evil.dll")); z.write(1); z.closeEntry() }
        }.toByteArray()
        assertTrue(runCatching { ModDownload.unzip(evil) }.exceptionOrNull() is SecurityException)
    }

    @Test fun deadRisingEntries() {
        for (id in listOf("dr2", "dr2otr")) {
            val g = games.getValue(id)
            val env = g.settings.env
            assertEquals("100", env["AUDIO_DIRECT_MS"]); assertEquals("25", env["AUDIO_DIRECT_PERIOD_MS"])
            assertEquals("d3d9.maxFrameRate = 60", env["DXVK_CONFIG"])
            assertEquals("1", env["FEX_TSOENABLED"])
            assertTrue(g.detectFiles.single().endsWith(".exe"))
            assertTrue(g.ui!!.manual != null)
            val controller = g.extras.single()
            assertEquals("controller", controller.kind)
            assertEquals(mapOf("WINEDLLOVERRIDES" to "dinput8=n,b"), controller.env)
            val mod = controller.tweaks.single().json
            assertTrue(mod.getString("url").startsWith("https://github.com/Clownacy/ClownDR2Fix/releases/download/"))
            assertTrue(mod.getString("sha256").matches(Regex("[0-9a-f]{64}")))
        }
        assertEquals(45740L, games.getValue("dr2").steamAppId)
        assertEquals(45770L, games.getValue("dr2otr").steamAppId)
        val otrFix = games.getValue("dr2otr").tweaks.single()
        assertEquals("iniKey", otrFix.type); assertEquals("UseMultiCore", otrFix.json.getString("key"))
        assertTrue(games.getValue("dr2").tweaks.isEmpty())
    }

    @Test fun hitmanHasFeatureLevelWithNodxr() {
        val env = games.getValue("hitman_woa").settings.env
        assertEquals("nodxr", env["VKD3D_CONFIG"]); assertEquals("12_1", env["VKD3D_FEATURE_LEVEL"])
    }

    @Test fun packsNeverShareKeysOrKinds() {
        // Otherwise reverting one pack could undo part of another.
        for (g in games.values) {
            val kinds = g.packs.map { it.kind }
            assertEquals(g.id, kinds.distinct(), kinds)
            val keys = g.packs.flatMap { it.env.keys }
            assertEquals(g.id, keys.distinct().size, keys.size)
        }
    }
}
