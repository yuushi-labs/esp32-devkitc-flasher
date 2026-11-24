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
                val finished = process.waitFor(60, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor(10, TimeUnit.SECONDS)
                    false
                } else {
                    process.inputStream.close()
                    process.exitValue() == 0
                }
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return null
    }

    /** Runs one command with a hard time limit; a hung child is killed and reported. */
    private fun run(vararg args: String): Pair<Int, String> {
        val python = python() ?: ""
        val process = ProcessBuilder(listOf(python) + args)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line -> output.append(line).append('\n') }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            reader.join(2_000)
            return -1 to "コマンドがタイムアウトしました: " + args.joinToString(" ") + "\n" + output
        }
        reader.join(5_000)
        return process.exitValue() to output.toString()
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
        val output = File("build/littlefs-interop/fixtures")
        output.deleteRecursively()
        output.mkdirs()

        val (code, text) = run(script.absolutePath, "fixtures", output.absolutePath)
        assertEquals("littlefs-python がイメージを作れませんでした:\n$text", 0, code)

        val manifest = output.resolve("manifest.tsv")
        assertTrue("manifest.tsv がありません", manifest.isFile)
        val expected = LinkedHashMap<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (line in manifest.readLines()) {
            if (line.isBlank()) continue
            if (!line.startsWith("  ")) {
                current = LinkedHashMap()
                expected[line.substringBefore('\t')] = current
            } else {
                current?.put(line.trim().substringBefore('\t'), line.trim().substringAfter('\t'))
            }
        }
        assertTrue("littlefs-python のシナリオがありません", expected.isNotEmpty())

        for ((name, files) in expected) {
            val image = output.resolve("$name.bin")
            assertTrue("$name.bin がありません", image.isFile)
            val reader = LittleFsReader(image.readBytes())
            val listed = reader.files().associateBy { it.path }
            assertEquals("$name のファイル一覧", files.keys, listed.keys)
            for ((path, digest) in files) {
                val data = reader.read(listed.getValue(path))
                assertEquals("$name / $path のサイズ", listed.getValue(path).size, data.size.toLong())
                assertEquals("$name / $path の内容", digest, sha256(data))
            }
        }
    }

    @Test
    fun `littlefs-python can write into a generated image without losing files`() {
        assumeTrue("python3 + littlefs-python がないためスキップします", python() != null)
        val output = File("build/littlefs-interop/append")
        output.deleteRecursively()
        output.mkdirs()

        val files = listOf(
            LittleFsFile("top.txt", "top".toByteArray()),
            LittleFsFile("docs/memo.txt", "memo".repeat(100).toByteArray()),
            LittleFsFile("docs/sub/deep.txt", "deep".toByteArray()),
            LittleFsFile("docs/sub/another.dat", ByteArray(300) { index -> (index % 251).toByte() }),
            LittleFsFile("big.bin", ByteArray(9000) { index -> (index * 5 % 256).toByte() }),
        )
        val image = LittleFsImageBuilder.build(files, partitionSize = 256 * 1024)
        output.resolve("image.bin").writeBytes(image)

        val (code, text) = run(script.absolutePath, "append", output.absolutePath)
        assertEquals("littlefs-python が書き込めませんでした:\n$text", 0, code)

        val lines = text.lines().filter { it.isNotBlank() }
        val added = lines.firstOrNull { it.startsWith("added\t") }
            ?: throw AssertionError("追加ファイルの結果がありません:\n$text")
        val parsed = lines.filter { it.contains('\t') && !it.startsWith("added\t") }
            .associate { line ->
                val parts = line.split('\t')
                parts[0] to parts[2]
            }
        // Every file that was in the image is still readable, byte for byte.
        for (file in files) {
            val path = LittleFsFile.normalizePath(file.path)
            assertEquals("$path が失われました: $text", sha256(file.data), parsed[path])
        }
        assertEquals(added.substringAfter('\t'), parsed["added-by-littlefs.bin"])
    }

    @Test
    fun `the interop script documents both directions`() {
        val text = script.readText()
        assertTrue(text.contains("littlefs-python"))
        assertTrue(text.contains("verify"))
        assertTrue(text.contains("fixtures"))
    }
}
