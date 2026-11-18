package com.goob.ddhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ScopeTest {
    private val pkg = "com.droiddeck.launcher"

    @Before fun setUp() {
        Scope.setDroidDeck(pkg)
        Scope.setLibraries(listOf("/storage/emulated/0/SteamLibrary"))
    }

    @Test fun allowsDroidDeckFolderAndRegisteredLibraries() {
        assertTrue(Scope.allows("/data/data/$pkg/files/game-environment.json"))
        assertTrue(Scope.allows("/data/user/0/$pkg/files/linuxfs/root/.local/share/Steam/steamapps/common/Maneater/Maneater.exe"))
        assertTrue(Scope.allows("/storage/emulated/0/SteamLibrary/steamapps/common/HITMAN 3/Retail/HITMAN3.exe"))
        assertTrue(Scope.isInsideDroidDeck("/data/data/$pkg/files/x"))
        assertFalse(Scope.isInsideDroidDeck("/storage/emulated/0/SteamLibrary/x"))
    }

    @Test fun refusesEverythingElse() {
        listOf(
            "/data/data/com.other.app/files/secret",
            "/data/data/$pkg.evil/files/x",          // a package whose name starts with DroidDeck's
            "/data/data/$pkg/../com.other.app/x",    // climbing out with ..
            "/data/data/$pkg/./files",
            "/storage/emulated/0/Download/file",
            "/storage/emulated/0/SteamLibraryX/x",   // sibling folder with a matching prefix
            "/system/build.prop",
            "relative/path",
            "/data/data/$pkg/files/a\nrm -rf /",
        ).forEach { assertFalse(it, Scope.allows(it)) }
    }

    @Test(expected = SecurityException::class)
    fun checkThrowsOutsideScope() = Scope.check("/data/data/com.other.app/files/x")

    @Test fun shallowLibraryRootsAreIgnored() {
        Scope.setLibraries(listOf("/", "/storage", "/storage/emulated", "/storage/emulated/0/Games/Steam"))
        assertEquals(listOf("/data/data/$pkg", "/storage/emulated/0/Games/Steam"), Scope.folders())
        assertFalse(Scope.allows("/storage/emulated/0/Pictures/x.jpg"))
    }

    @Test fun everyGameHasDetectFiles() {
        GameDb.parse(File("src/main/assets/games.json").readText()).forEach { g ->
            assertTrue(g.id, g.detectFiles.isNotEmpty())
            g.detectFiles.forEach { f -> assertFalse(f, f.startsWith("/") || f.contains("..")) }
        }
    }
}
