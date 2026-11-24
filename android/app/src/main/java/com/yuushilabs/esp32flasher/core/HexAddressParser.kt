package com.yuushilabs.esp32flasher.core

/** Well known flash offsets offered by the UI. */
enum class AddressPreset(val address: Long, val label: String) {
    /** A merged image that already contains bootloader, partition table and application. */
    MERGED(0x0000L, "結合済み"),

    /** Second stage bootloader of the default partition table. */
    BOOTLOADER(0x1000L, "ブートローダー"),

    /** Partition table of the default partition table. */
    PARTITION_TABLE(0x8000L, "パーティションテーブル"),

    /** `otadata` of an ESP32 (Arduino) layout. */
    OTA_DATA(0xE000L, "OTADATA"),

    /** An application image written right after the default partition table. */
    APP(0x10000L, "アプリ単体"),

    /** `spiffs`/`littlefs` data partition of the Arduino `default.csv` 4 MB layout. */
    LITTLEFS_DEFAULT(0x290000L, "LittleFS (4MB default)"),

    /** Data partition of the Arduino `min_spiffs.csv` 4 MB layout. */
    LITTLEFS_MIN_SPIFFS(0x3D0000L, "LittleFS (min_spiffs)"),
}

/**
 * Parses and validates flash offsets typed by the user.
 *
 * Flash offsets are hexadecimal, so bare digits are read as hex (`10000` == `0x10000`) and the
 * `0x` prefix is optional. A `0d` decimal prefix is not accepted: `0D00` is a valid hexadecimal
 * offset and reading it as decimal `0` would silently move the write to address 0.
 */
object HexAddressParser {
    const val SECTOR_SIZE = 4096

    /** One past the highest addressable byte of a 16 MB flash (`0xFFFFFF`). */
    const val MAX_ADDRESS = 0x1000000L
    const val MIN_ADDRESS = 0x0L

    private val HEX_DIGITS = Regex("^[0-9a-fA-F]+$")

    fun parse(input: String): Result<Long> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(InvalidAddressException("アドレスを入力してください"))
        }
        val digits = if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.substring(2)
        } else {
            trimmed
        }
        if (digits.isEmpty()) {
            return Result.failure(InvalidAddressException("アドレスの数値が空です: $input"))
        }
        if (!HEX_DIGITS.matches(digits)) {
            return Result.failure(
                InvalidAddressException(
                    "アドレスの形式が不正です: $input (例: 0x10000 / 0x0000)",
                ),
            )
        }
        val value = digits.toLongOrNull(16)
            ?: return Result.failure(InvalidAddressException("アドレスが大きすぎます: $input"))
        if (value < MIN_ADDRESS || value >= MAX_ADDRESS) {
            return Result.failure(
                InvalidAddressException(
                    "アドレスは 0x000000〜0xFFFFFF の範囲で指定してください: $input",
                ),
            )
        }
        return Result.success(value)
    }

    fun parseOrThrow(input: String): Long = parse(input).getOrElse { throw it }

    fun format(address: Long): String {
        val hex = address.toString(16).uppercase()
        val padded = if (hex.length < 4) hex.padStart(4, '0') else hex
        return "0x$padded"
    }

    fun isSectorAligned(address: Long): Boolean = address % SECTOR_SIZE == 0L

    fun alignDown(address: Long, sectorSize: Int = SECTOR_SIZE): Long =
        address - (address % sectorSize)

    fun alignUp(address: Long, sectorSize: Int = SECTOR_SIZE): Long {
        val remainder = address % sectorSize
        return if (remainder == 0L) address else address + (sectorSize - remainder)
    }
}

class InvalidAddressException(message: String) : IllegalArgumentException(message)
