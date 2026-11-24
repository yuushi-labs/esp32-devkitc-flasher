package com.yuushilabs.esp32flasher.core

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One RAM segment of the stub flasher. */
data class StubSegment(val address: Long, val data: ByteArray)

/**
 * The Espressif stub flasher, as distributed in `esptool` (`stub_flasher/2/esp32.json`, released
 * from https://github.com/espressif/esp-flasher-stub, Apache-2.0 / MIT).
 *
 * A bare ESP32 ROM does **not** implement `READ_FLASH` (0xD2): only this stub does. The app copies
 * it into RAM with `MEM_BEGIN`/`MEM_DATA`/`MEM_END`, waits for its `OHAI` greeting, and can then
 * read flash at full speed. When the upload fails the engine falls back to the ROM's slow
 * `READ_FLASH_SLOW` (0x0E), which still works on an ESP32 without any stub.
 */
data class EspStub(
    val entry: Long,
    val segments: List<StubSegment>,
) {
    val totalBytes: Int get() = segments.sumOf { it.data.size }
}

class StubFormatException(message: String) : Exception(message)

/** Parses the JSON form used by esptool for its stub flasher binaries. */
object EspStubParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): EspStub {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw StubFormatException("スタブの JSON を解析できません")
        val entry = root.number("entry")
            ?: throw StubFormatException("スタブの entry がありません")
        val segments = ArrayList<StubSegment>()
        val text64 = root.string("text")
        val textStart = root.number("text_start")
        if (text64 != null && textStart != null) {
            segments += StubSegment(textStart, decode(text64, "text"))
        }
        val data64 = root.string("data")
        val dataStart = root.number("data_start")
        if (data64 != null && dataStart != null) {
            segments += StubSegment(dataStart, decode(data64, "data"))
        }
        if (segments.isEmpty()) throw StubFormatException("スタブのセグメントがありません")
        return EspStub(entry = entry, segments = segments)
    }

    private fun decode(value: String, label: String): ByteArray =
        runCatching { Base64.getDecoder().decode(value) }.getOrElse {
            throw StubFormatException("スタブの $label を base64 として読めません")
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}
