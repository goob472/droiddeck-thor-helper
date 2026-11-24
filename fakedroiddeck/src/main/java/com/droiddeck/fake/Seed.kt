package com.droiddeck.fake

import java.io.File

/**
 * Fake DroidDeck layout: Steam inside the Linux runtime, a few installed games in different states,
 * a Steam account config and a Steam library registered on shared storage.
 */
object Seed {
    fun ensure(dataDir: File) {
        val marker = File(dataDir, "files/.seeded")
        if (marker.exists()) return
        val files = File(dataDir, "files")
        val steam = File(files, "linuxfs/root/.local/share/Steam")
        fun put(path: String, text: String) = File(steam, path).apply { parentFile!!.mkdirs(); writeText(text) }
        fun manifest(appId: Long, dir: String) = put(
            "steamapps/appmanifest_$appId.acf",
            "\"AppState\"\n{\n\t\"appid\"\t\t\"$appId\"\n\t\"installdir\"\t\t\"$dir\"\n\t\"StateFlags\"\t\t\"4\"\n}\n",
        )

        File(files, "game-environment.json").apply { parentFile!!.mkdirs(); writeText("{\"version\":1,\"shared\":{},\"games\":{}}") }
        put(
            "steamapps/libraryfolders.vdf",
            "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"/root/.local/share/Steam\"\n\t}\n" +
                "\t\"1\"\n\t{\n\t\t\"path\"\t\t\"/root/Storage/SteamLibrary\"\n\t}\n}\n",
        )
        put(
            "userdata/12345/config/localconfig.vdf",
            "\"UserLocalConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n" +
                "\t\t\t\t\"apps\"\n\t\t\t\t{\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n",
        )

        // Maneater: a normal, complete install.
        manifest(629820, "Maneater")
        put("steamapps/common/Maneater/Maneater.exe", "MZ fake")
        put("steamapps/common/Maneater/Maneater/Content/Movies/TWI_Logo_1080.mp4", "fake movie")
        put("steamapps/compatdata/629820/pfx/drive_c/users/steamuser/AppData/Local/Maneater/Saved/Config/WindowsNoEditor/Engine.ini", "[Core.System]\r\nPaths=../../Engine\r\n")

        // Off the Record: exe and settings folder in a different letter case than the helper expects.
        manifest(45770, "Dead Rising 2 Off The Record")
        put("steamapps/common/Dead Rising 2 Off The Record/DeadRising2OTR.exe", "MZ fake")
        put(
            "steamapps/compatdata/45770/pfx/drive_c/users/steamuser/Documents/My Games/Dead Rising 2 Off the Record/rendersettings.ini",
            "Width = 1280\r\nHeight = 720\r\nUseMultiCore = true\r\n",
        )

        // Disgaea 2: complete, with a game.cfg to reset.
        manifest(495280, "Disgaea 2 PC")
        put("steamapps/common/Disgaea 2 PC/disgaea2.exe", "MZ fake")
        put("steamapps/common/Disgaea 2 PC/game.cfg", "old map")

        // HITMAN: Steam has a manifest, but the game files are incomplete (no executable).
        manifest(1659040, "HITMAN 3")
        put("steamapps/common/HITMAN 3/Launcher.exe", "MZ fake")

        marker.writeText("1")
    }
}
