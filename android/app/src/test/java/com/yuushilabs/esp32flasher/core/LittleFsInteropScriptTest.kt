package com.yuushilabs.esp32flasher.core

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real interoperability check against `littlefs-python` (the reference C implementation)
 * when the machine has it: images written by [LittleFsImageBuilder] are mounted by littlefs-python
 * and compared byte for byte, and images written by littlefs-python are read back by
 * [LittleFsReader].
 *
 * The script is `tools/littlefs_interop.py`; install the dependency with
 * `pip install littlefs-python`. When Python or the module is missing the test is skipped instead
 * of failing, because the Gradle build must stay runnable without a Python toolchain; the
 * committed fixtures in `LittleFsFixtureTest` always run.
 */
class LittleFsInteropScriptTest {
    private val script: File
        get() {
            val candidates = listOf(
                File("src/../../../tools/littlefs_interop.py"),
                File("../tools/littlefs_interop.py"),
                File("tools/littlefs_interop.py"),
            )
            return candidates.firstOrNull { it.isFile }?.canonicalFile
                ?: throw AssertionError("tools/littlefs_interop.py が見つかりません")
        }

    private fun python(): String? {
        val candidates = listOf(
            System.getenv("LITTLEFS_PYTHON") ?: "python3",
            "python",
        )
        for (candidate in candidates) {
            val ok = runCatching {
                val process = ProcessBuilder(candidate, "-c", "import littlefs, sys; sys.exit(0)")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return null
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val python = python() ?: ""
        val process = ProcessBuilder(listOf(python) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(180, TimeUnit.SECONDS)
        return process.exitValue() to output
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `littlefs-python mounts the images this app writes`() {
        assumeTrue("python3 + littlefs-python がないためスキップします", python() != null)
        val output = File("build/littlefs-interop/verify").apply { mkdirs() }

        val scenarios = mapOf(
            "empty" to emptyList<LittleFsFile>(),
            "single" to listOf(LittleFsFile("hello.txt", "hello littlefs\n".toByteArray())),
            "nested" to listOf(
                LittleFsFile("docs/memo.txt", "memo".repeat(100).toByteArray()),
                LittleFsFile("docs/sub/deep.txt", "deep".toByteArray()),
                LittleFsFile("docs/sub/data.bin", ByteArray(300) { (it % 251).toByte() }),
                LittleFsFile("日本語.txt", "こんにちは、世界！".toByteArray()),
            ),
            "big" to listOf(LittleFsFile("big.bin", ByteArray(20000) { (it * 7 % 256).toByte() })),
            "many" to (0 until 150).map {
                LittleFsFile("capture-2026-11-24-%03d.jpg".format(it), ByteArray(1200 + it) { i -> i.toByte() })
            },
        )
        val manifest = StringBuilder()
        for ((name, files) in scenarios) {
            val partitionSize = if (name == "many") 1024 * 1024 else 128 * 1024
            val image = LittleFsImageBuilder.build(files, partitionSize = partitionSize)
            val path = File(output, "$name.bin")
            path.writeBytes(image)
            manifest.append("$name\t${path.absolutePath}\n")
            for (file in files) {
                manifest.append("  ${LittleFsFile.normalizePath(file.path)}\t${sha256(file.data)}\n")
            }
        }
        File(output, "manifest.tsv").writeText(manifest.toString())

        val (code, text) = run(script.absolutePath, "verify", output.absolutePath)
        assertTrue("littlefs-python の検証に失敗しました:\n$text", text.contains("[ OK ]"))
        assertEquals("littlefs-python の検証に失敗しました:\n$text", 0, code)
    }

    @Test
    fun `this app reads the images littlefs-python writes`() {
        assumeTrue("python3 + littlefs-python がないためスキップします", python() != null)
        val output = File("build/littlefs-interop/fixtures").apply { mkdirs() }

        val (code, text) = run(script.absolutePath, "fixtures", output.absolutePath)
        assertEquals("littlefs-python がイメージを作れませんでした:\n$text", 0, code)

        val images = output.listFiles { file -> file.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }
        assertTrue(text, images.isNotEmpty())

        var checked = 0
        for (file in images) {
            val reader = runCatching { LittleFsReader(file.readBytes()) }.getOrNull() ?: continue
            for (entry in reader.files()) {
                val data = reader.read(entry)
                assertEquals(entry.size, data.size.toLong())
                checked++
            }
        }
        assertTrue("littlefs-python のイメージを1件も読めませんでした", checked > 0)
    }

    @Test
    fun `the interop script documents both directions`() {
        val text = script.readText()
        assertTrue(text.contains("littlefs-python"))
        assertTrue(text.contains("verify"))
        assertTrue(text.contains("fixtures"))
    }
}
