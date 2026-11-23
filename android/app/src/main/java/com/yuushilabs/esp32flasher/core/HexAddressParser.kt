package com.yuushilabs.esp32flasher.core

/** Well known flash offsets offered by the UI. */
enum class AddressPreset(val address: Long) {
    /** A merged image that already contains bootloader, partition table and application. */
    MERGED(0x0000L),

    /** An application image written right after the default partition table. */
    APP(0x10000L),
}

/**
 * Parses and validates flash offsets typed by the user.
 *
 * Bare hexadecimal digits are accepted (`10000` == `0x10000`) because flash offsets are almost
 * always written in hexadecimal, and `0x` / `0d` prefixes force an explicit base.
 */
object HexAddressParser {
    const val SECTOR_SIZE = 4096
    const val MAX_ADDRESS = 0x1000000L // 16 MB, the largest ESP32 flash size
    const val MIN_ADDRESS = 0x0L

    private val HEX_DIGITS = Regex("^[0-9a-fA-F]+$")
    private val DECIMAL_DIGITS = Regex("^[0-9]+$")

    fun parse(input: String): Result<Long> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(InvalidAddressException("アドレスを入力してください"))
        }
        val (digits, radix) = when {
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.substring(2) to 16
            trimmed.startsWith("0d", ignoreCase = true) -> trimmed.substring(2) to 10
            else -> trimmed to 16
        }
        if (digits.isEmpty()) {
            return Result.failure(InvalidAddressException("アドレスの数値が空です: $input"))
        }
        val valid = if (radix == 16) HEX_DIGITS.matches(digits) else DECIMAL_DIGITS.matches(digits)
        if (!valid) {
            return Result.failure(
                InvalidAddressException(
                    "アドレスの形式が不正です: $input (例: 0x10000 / 0x0000)",
                ),
            )
        }
        val value = digits.toLongOrNull(radix)
            ?: return Result.failure(InvalidAddressException("アドレスが大きすぎます: $input"))
        if (value < MIN_ADDRESS || value > MAX_ADDRESS) {
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
