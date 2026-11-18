package com.goob.ddhelper

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Generates the real write scripts into build/device-scripts so they can be run on an emulator or
 * device (see tools/check_write_scripts.sh). Checks here cover the script's shape; the device run
 * checks bytes, ownership and SELinux labels.
 */
class WriteScriptTest {
    private val base = "/data/data/com.goob.ddhelper/files/ddh-write-test"

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun generateDeviceScripts() {
        val out = File("build/device-scripts").apply { deleteRecursively(); mkdirs() }
        val cases = linkedMapOf(
            "new-nested" to ("$base/a b/c'd/Engine.ini" to "[SystemSettings]\r\nr.Foo=1\r\nÜnïcødé=✓\r\n".toByteArray()),
            "overwrite" to ("$base/a b/c'd/Engine.ini" to "[SystemSettings]\nr.Foo=2\n".toByteArray()),
            "empty" to ("$base/empty.txt" to ByteArray(0)),
        )
        val expect = StringBuilder()
        for ((name, case) in cases) {
            val (path, bytes) = case
            val script = Root.writeScript(path, bytes)
            assertTrue(script.contains("\nDDH_B64_END\n"))
            File(out, "$name.sh").writeText(script)
            expect.appendLine("$name\t$path\t${sha(bytes)}")
        }
        File(out, "expected.tsv").writeText(expect.toString())
    }
}
