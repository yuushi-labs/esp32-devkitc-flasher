package com.yuushilabs.esp32flasher.usb

import com.yuushilabs.esp32flasher.core.EspBootloaderProtocol
import com.yuushilabs.esp32flasher.core.EspCommands
import com.yuushilabs.esp32flasher.core.EspProtocolException
import com.yuushilabs.esp32flasher.core.EspResponse
import com.yuushilabs.esp32flasher.core.FlashPlan
import com.yuushilabs.esp32flasher.core.HexAddressParser
import com.yuushilabs.esp32flasher.core.SlipDecoder

/** Phases reported while connecting and flashing, used by the UI progress panel. */
enum class FlashStage {
    CONNECTING,
    SYNCING,
    CHIP_INFO,
    ATTACHING,
    ERASING,
    WRITING,
    FINISHING,
    RESETTING,
    DONE,
}

data class ChipInfo(
    val name: String,
    val macAddress: String? = null,
) {
    val displayName: String
        get() = if (macAddress != null) "$name ($macAddress)" else name
}

data class FlashProgress(
    val bytesWritten: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((bytesWritten * 100) / totalBytes).toInt().coerceIn(0, 100)

    val kilobytesPerSecond: Int get() = (bytesPerSecond / 1024).toInt()
}

interface FlashListener {
    fun onStage(stage: FlashStage) = Unit

    fun onLog(message: String) = Unit

    fun onProgress(progress: FlashProgress) = Unit

    companion object {
        val NONE: FlashListener = object : FlashListener {}
    }
}

data class FlashConfig(
    val compression: Boolean = true,
    val autoReset: Boolean = true,
    val syncAttempts: Int = 7,
    val syncTimeoutMs: Int = 250,
    val responseTimeoutMs: Int = 3_000,
    val blockTimeoutMs: Int = 5_000,
    val eraseTimeoutMs: Int = 30_000,
    val resetAfterFlash: Boolean = true,
    /** Rate the link is raised to after a successful sync; the ROM always syncs at 115200. */
    val targetBaudRate: Int = EspFlasherEngine.DEFAULT_TARGET_BAUD_RATE,
)

sealed class FlashOutcome {
    data class Success(
        val transferredBytes: Int,
        val uncompressedBytes: Int,
        val compressed: Boolean,
        val elapsedMs: Long,
        val chip: ChipInfo?,
    ) : FlashOutcome()

    data class Failure(val message: String, val cause: Throwable? = null) : FlashOutcome()

    data object Cancelled : FlashOutcome()
}

class EspTimeoutException(message: String) : Exception(message)

class FlashCancelledException : Exception("書き込みを中止しました")

/**
 * Drives the ESP32 ROM bootloader: DTR/RTS boot sequence, SYNC, SPI_ATTACH and
 * FLASH_(DEFL_)BEGIN / DATA / END with an uncompressed fallback.
 *
 * The class only depends on [SerialLink], so the entire sequence can be verified on the JVM
 * against a simulated ROM bootloader.
 */
class EspFlasherEngine(
    private val link: SerialLink,
    private val config: FlashConfig = FlashConfig(),
    private val listener: FlashListener = FlashListener.NONE,
    private val clock: () -> Long = { System.nanoTime() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val cancellation: () -> Boolean = { false },
) {
    private val decoder = SlipDecoder()
    private val pendingFrames = ArrayDeque<ByteArray>()
    private val readBuffer = ByteArray(READ_BUFFER_SIZE)

    var chipInfo: ChipInfo? = null
        private set

    var isSynced: Boolean = false
        private set

    /**
     * Runs the boot sequence (when enabled) and synchronises with the ROM bootloader.
     *
     * The ROM only listens at [INITIAL_SYNC_BAUD], whatever rate the port was opened at, so the
     * link is put back to that rate before DTR/RTS, SYNC and the chip reads. Only once the chip
     * has answered is the link raised to [FlashConfig.targetBaudRate] through CHANGE_BAUDRATE;
     * when the USB-UART bridge cannot follow, the flash simply continues at 115200.
     */
    fun connect(): ChipInfo {
        listener.onStage(FlashStage.CONNECTING)
        switchToInitialBaudRate()
        if (config.autoReset) {
            performBootloaderReset()
        }
        sync()
        val info = readChipInfo()
        chipInfo = info
        isSynced = true
        listener.onLog("接続しました: ${info.displayName}")
        switchToTargetBaudRate()
        return info
    }

    /** Writes [image] at [offset]. Never throws for protocol errors; inspect the outcome. */
    fun flash(image: ByteArray, offset: Long, compression: Boolean = config.compression): FlashOutcome {
        if (image.isEmpty()) {
            return FlashOutcome.Failure("書き込むイメージが空です")
        }
        val startedAt = clock()
        return try {
            if (!isSynced) {
                connect()
            }
            attachFlash()

            var plan = EspBootloaderProtocol.flashPlan(image, offset, compression)
            var compressed = plan.compressed
            if (compressed) {
                try {
                    writePlan(plan)
                } catch (error: EspProtocolException) {
                    listener.onLog("圧縮書き込みに失敗しました (${error.message})。非圧縮で再試行します")
                    resetProtocolState()
                    attachFlash()
                    plan = EspBootloaderProtocol.flashPlan(image, offset, compression = false)
                    compressed = false
                    writePlan(plan)
                }
            } else {
                writePlan(plan)
            }

            if (config.resetAfterFlash) {
                hardReset()
            }
            listener.onStage(FlashStage.DONE)
            val elapsedMs = (clock() - startedAt) / NANOS_PER_MILLI
            listener.onLog(
                "書き込み完了: %d バイト (%s) を %s に書き込みました".format(
                    plan.uncompressedSize,
                    if (compressed) "圧縮" else "非圧縮",
                    HexAddressParser.format(offset),
                ),
            )
            FlashOutcome.Success(
                transferredBytes = plan.payload.size,
                uncompressedBytes = plan.uncompressedSize,
                compressed = compressed,
                elapsedMs = elapsedMs,
                chip = chipInfo,
            )
        } catch (cancelled: FlashCancelledException) {
            listener.onLog("書き込みを中止しました")
            FlashOutcome.Cancelled
        } catch (error: Exception) {
            listener.onLog("書き込みに失敗しました: ${error.message}")
            FlashOutcome.Failure(error.message ?: "書き込みに失敗しました", error)
        }
    }

    /** Asserts that the bootloader is reachable without flashing anything. */
    fun probe(): ChipInfo {
        val info = if (isSynced) chipInfo ?: readChipInfo() else connect()
        chipInfo = info
        isSynced = true
        return info
    }

    fun hardReset() {
        listener.onStage(FlashStage.RESETTING)
        listener.onLog("ハードリセットを実行します")
        link.setDtrRts(false, true)
        sleep(RESET_PULSE_MS)
        link.setDtrRts(false, false)
        sleep(RESET_SETTLE_MS)
        link.flushInput()
        isSynced = false
    }

    /**
     * Switches the ROM bootloader and the USB-UART bridge to [newBaud].
     *
     * Returns false when the change could not be made, after putting both sides back on
     * [currentBaud]: a rejected request leaves the ROM where it was, and a bridge that accepts
     * nothing at the new rate is recovered by a bootloader reset (the ROM boots at 115200 again).
     * Throws [EspProtocolException] when that recovery cannot re-establish the link.
     */
    fun changeBaudRate(newBaud: Int, currentBaud: Int = link.baudRate): Boolean {
        if (newBaud <= 0 || newBaud == currentBaud) return true

        val accepted = try {
            val response = commandFrame(
                EspCommands.CHANGE_BAUDRATE,
                EspBootloaderProtocol.changeBaudFrame(newBaud, currentBaud),
                config.responseTimeoutMs,
            )
            requireOk(EspCommands.CHANGE_BAUDRATE, response)
            true
        } catch (cancelled: FlashCancelledException) {
            throw cancelled
        } catch (error: Exception) {
            listener.onLog("ボーレート変更に失敗しました: ${error.message}")
            false
        }

        if (!accepted) {
            // A refusal keeps the ROM at the old rate. A lost reply is ambiguous - the ROM may
            // already have switched - so the link is checked at the old rate before continuing.
            if (!isBootloaderReachableAt(currentBaud)) {
                listener.onLog("ESP32 が応答しません。ボーレートがずれたためリセットして復旧します")
                recoverAtSyncRate(currentBaud)
            }
            return false
        }

        sleep(BAUD_SWITCH_DELAY_MS)
        val switched = runCatching { link.setBaudRate(newBaud) }.isSuccess
        decoder.reset()
        pendingFrames.clear()
        if (!switched) {
            // The ROM is at newBaud while the bridge still is not: only a reset brings them back.
            listener.onLog("USB 側を $newBaud bps に切り替えられませんでした。ESP32 をリセットして復旧します")
            recoverAtSyncRate(currentBaud)
            return false
        }
        return try {
            val magic = commandFrame(
                EspCommands.READ_REG,
                EspBootloaderProtocol.readRegFrame(CHIP_MAGIC_REGISTER),
                config.responseTimeoutMs,
            )
            listener.onLog("ボーレートを $newBaud bps に変更しました (magic=0x%08X)".format(magic.value))
            true
        } catch (cancelled: FlashCancelledException) {
            throw cancelled
        } catch (error: Exception) {
            listener.onLog("$newBaud bps で応答がありません: ${error.message}")
            recoverAtSyncRate(currentBaud)
            false
        }
    }

    /** True when the ROM bootloader answers a register read at [baudRate]. */
    private fun isBootloaderReachableAt(baudRate: Int): Boolean {
        runCatching { link.setBaudRate(baudRate) }
        decoder.reset()
        pendingFrames.clear()
        runCatching { link.flushInput() }
        return try {
            commandFrame(
                EspCommands.READ_REG,
                EspBootloaderProtocol.readRegFrame(CHIP_MAGIC_REGISTER),
                config.responseTimeoutMs,
            )
            true
        } catch (cancelled: FlashCancelledException) {
            throw cancelled
        } catch (error: Exception) {
            false
        }
    }

    /**
     * Puts the ROM and the bridge back in step after a baud-rate change went wrong. A bootloader
     * reset returns the ROM's UART to 115200, so both sides meet there again; without the auto
     * reset the caller has to reset the board by hand and [sync] reports it.
     */
    private fun recoverAtSyncRate(fallbackBaud: Int) {
        runCatching { link.setBaudRate(fallbackBaud) }
        decoder.reset()
        pendingFrames.clear()
        isSynced = false
        if (config.autoReset) {
            listener.onLog("ESP32 をリセットして ${INITIAL_SYNC_BAUD} bps で再同期します")
            performBootloaderReset()
        }
        try {
            sync()
        } catch (error: EspProtocolException) {
            throw EspProtocolException("ボーレート変更後の復旧に失敗しました: ${error.message}")
        }
        isSynced = true
    }

    fun performBootloaderReset() {
        listener.onLog("DTR/RTS でブートローダーへ突入します")
        // esptool classic reset: EN low while IO0 stays high, then IO0 low while EN goes high.
        link.setDtrRts(false, true)
        sleep(RESET_PULSE_MS)
        link.setDtrRts(true, false)
        sleep(RESET_PULSE_MS)
        link.setDtrRts(false, false)
        sleep(RESET_SETTLE_MS)
        link.flushInput()
        decoder.reset()
        pendingFrames.clear()
        sleep(RESET_SETTLE_MS)
    }

    /**
     * Puts the link back to [INITIAL_SYNC_BAUD] before a sync. After a flash the link may still
     * be at the fast rate, and a failed CHANGE_BAUDRATE may leave the bridge anywhere, so this
     * runs before every SYNC burst instead of only once.
     */
    private fun switchToInitialBaudRate() {
        if (link.baudRate == INITIAL_SYNC_BAUD) return
        listener.onLog(
            "SYNC は ${INITIAL_SYNC_BAUD} bps で行うため、ボーレートを ${link.baudRate} bps から戻します",
        )
        runCatching { link.setBaudRate(INITIAL_SYNC_BAUD) }
        decoder.reset()
        pendingFrames.clear()
        runCatching { link.flushInput() }
    }

    private fun switchToTargetBaudRate() {
        val target = config.targetBaudRate
        if (target <= 0 || target == INITIAL_SYNC_BAUD) return
        if (link.baudRate == target) return
        if (!changeBaudRate(target, INITIAL_SYNC_BAUD)) {
            listener.onLog("${INITIAL_SYNC_BAUD} bps のまま書き込みを続けます")
        }
    }

    private fun sync() {
        listener.onStage(FlashStage.SYNCING)
        switchToInitialBaudRate()
        for (attempt in 1..config.syncAttempts) {
            checkCancelled()
            if (attempt > 1 && attempt % 3 == 0 && config.autoReset) {
                performBootloaderReset()
            }
            listener.onLog("SYNC 送信 ($attempt/${config.syncAttempts})")
            link.write(EspBootloaderProtocol.syncFrame())
            try {
                val response = readResponse(config.syncTimeoutMs)
                if (response.command == EspCommands.SYNC) {
                    drainSyncResponses()
                    listener.onLog("SYNC 応答を受信しました")
                    return
                }
            } catch (timeout: EspTimeoutException) {
                // Retry with the next SYNC packet.
            } catch (protocol: EspProtocolException) {
                listener.onLog("SYNC 応答を解釈できませんでした: ${protocol.message}")
            }
        }
        throw EspProtocolException(
            "ESP32 と同期できませんでした。BOOT ボタンを押しながらリセットし、もう一度お試しください。",
        )
    }

    private fun readChipInfo(): ChipInfo {
        listener.onStage(FlashStage.CHIP_INFO)
        val name = try {
            val response = commandFrame(
                EspCommands.READ_REG,
                EspBootloaderProtocol.readRegFrame(CHIP_MAGIC_REGISTER),
                config.responseTimeoutMs,
            )
            EspBootloaderProtocol.detectChip(response.value)
                ?: "ESP32 (magic 0x%08X)".format(response.value)
        } catch (error: EspProtocolException) {
            listener.onLog("チップ種別を取得できませんでした: ${error.message}")
            "ESP32"
        }
        val mac = try {
            val low = commandFrame(
                EspCommands.READ_REG,
                EspBootloaderProtocol.readRegFrame(MAC_LOW_REGISTER),
                config.responseTimeoutMs,
            )
            val high = commandFrame(
                EspCommands.READ_REG,
                EspBootloaderProtocol.readRegFrame(MAC_HIGH_REGISTER),
                config.responseTimeoutMs,
            )
            EspBootloaderProtocol.formatMac(high.value, low.value)
        } catch (error: EspProtocolException) {
            listener.onLog("MAC アドレスを取得できませんでした: ${error.message}")
            null
        }
        return ChipInfo(name, mac)
    }

    private fun attachFlash() {
        listener.onStage(FlashStage.ATTACHING)
        val response = commandFrame(
            EspCommands.SPI_ATTACH,
            EspBootloaderProtocol.spiAttachFrame(),
            config.responseTimeoutMs,
        )
        requireOk(EspCommands.SPI_ATTACH, response)
        listener.onLog("内蔵 SPI フラッシュを有効化しました")
    }

    private fun writePlan(plan: FlashPlan) {
        listener.onStage(FlashStage.ERASING)
        listener.onLog(
            "フラッシュを消去して転送を開始します (消去 %d バイト / %d ブロック)".format(
                plan.eraseSize,
                plan.numBlocks,
            ),
        )
        val beginOp = if (plan.compressed) EspCommands.FLASH_DEFL_BEGIN else EspCommands.FLASH_BEGIN
        val begin = commandFrame(
            beginOp,
            EspBootloaderProtocol.flashBeginFrame(
                size = plan.uncompressedSize,
                numBlocks = plan.numBlocks,
                blockSize = plan.blockSize,
                offset = plan.offset,
                compressed = plan.compressed,
            ),
            config.eraseTimeoutMs,
        )
        requireOk(beginOp, begin)

        listener.onStage(FlashStage.WRITING)
        val dataOp = if (plan.compressed) EspCommands.FLASH_DEFL_DATA else EspCommands.FLASH_DATA
        val startedAt = clock()
        var written = 0L
        val chunks = plan.chunks()
        chunks.forEachIndexed { sequence, chunk ->
            checkCancelled()
            val response = commandFrame(
                dataOp,
                EspBootloaderProtocol.flashDataFrame(chunk, sequence, plan.compressed),
                config.blockTimeoutMs,
            )
            requireOk(dataOp, response)
            written += chunk.size
            val elapsed = (clock() - startedAt).coerceAtLeast(1L)
            listener.onProgress(
                FlashProgress(
                    bytesWritten = written,
                    totalBytes = plan.payload.size.toLong(),
                    bytesPerSecond = (written * NANOS_PER_SECOND) / elapsed,
                ),
            )
        }

        listener.onStage(FlashStage.FINISHING)
        val endOp = if (plan.compressed) EspCommands.FLASH_DEFL_END else EspCommands.FLASH_END
        val end = commandFrame(
            endOp,
            EspBootloaderProtocol.flashEndFrame(stayInLoader = false, compressed = plan.compressed),
            config.responseTimeoutMs,
        )
        requireOk(endOp, end)
    }

    private fun commandFrame(op: Int, frame: ByteArray, timeoutMs: Int): EspResponse {
        link.write(frame)
        var response = readResponse(timeoutMs)
        var skipped = 0
        while (response.command != op && skipped < MAX_SKIPPED_FRAMES) {
            listener.onLog("想定外の応答 (0x%02X) を無視します".format(response.command))
            response = readResponse(timeoutMs)
            skipped++
        }
        if (response.command != op) {
            throw EspProtocolException(
                "応答コマンドが一致しません: 0x%02X (期待 0x%02X)".format(response.command, op),
            )
        }
        return response
    }

    private fun readResponse(timeoutMs: Int): EspResponse {
        val deadline = clock() + timeoutMs.toLong() * NANOS_PER_MILLI
        while (true) {
            checkCancelled()
            val frame = pendingFrames.removeFirstOrNull()
            if (frame != null) {
                // Boot text or a truncated frame can show up before the real reply; skip it
                // instead of failing the whole sequence.
                val parsed = runCatching { EspBootloaderProtocol.parseResponse(frame) }.getOrNull()
                if (parsed != null) return parsed
                listener.onLog("不正な応答フレーム (${frame.size} バイト) を読み捨てました")
                continue
            }
            val remainingMs = (deadline - clock()) / NANOS_PER_MILLI
            if (remainingMs <= 0) {
                throw EspTimeoutException("ESP32 からの応答がタイムアウトしました (${timeoutMs}ms)")
            }
            val readTimeout = remainingMs.coerceIn(1L, MAX_READ_TIMEOUT_MS).toInt()
            val count = link.read(readBuffer, readTimeout)
            if (count > 0) {
                decoder.feed(readBuffer, 0, count).forEach { pendingFrames.addLast(it) }
            }
        }
    }

    /**
     * The ROM answers a single SYNC packet with 8 identical responses (esptool reads and discards
     * the extra 7). Leaving them queued would shift the following `READ_REG` by a frame, so the
     * chip magic and MAC would be wrong.
     */
    private fun drainSyncResponses() {
        val deadline = clock() + SYNC_DRAIN_MS * NANOS_PER_MILLI
        while (clock() < deadline) {
            val count = link.read(readBuffer, SYNC_DRAIN_READ_MS)
            if (count <= 0) break
            decoder.feed(readBuffer, 0, count)
        }
        decoder.reset()
        pendingFrames.clear()
    }

    private fun requireOk(op: Int, response: EspResponse) {
        if (!response.hasStatus) {
            throw EspProtocolException(
                "コマンド 0x%02X の応答にステータスがありません".format(op),
            )
        }
        if (!response.isSuccess) {
            throw EspProtocolException(
                "コマンド 0x%02X が失敗しました (status=%d, error=%d)".format(
                    op,
                    response.status,
                    response.errorCode,
                ),
            )
        }
    }

    private fun resetProtocolState() {
        decoder.reset()
        pendingFrames.clear()
        isSynced = false
        link.flushInput()
        if (config.autoReset) performBootloaderReset()
        sync()
        isSynced = true
        // sync() dropped back to 115200; raise the link again so the retry keeps the fast rate.
        switchToTargetBaudRate()
    }

    private fun checkCancelled() {
        if (cancellation()) throw FlashCancelledException()
    }

    private fun sleep(milliseconds: Long) {
        checkCancelled()
        sleeper(milliseconds)
        checkCancelled()
    }

    companion object {
        const val CHIP_MAGIC_REGISTER = 0x40001000L
        const val MAC_LOW_REGISTER = 0x3FF5A004L
        const val MAC_HIGH_REGISTER = 0x3FF5A008L

        /** Rate the ROM bootloader always answers SYNC at. */
        const val INITIAL_SYNC_BAUD = 115_200

        /** Upload rate the app aims for once the bootloader has answered. */
        const val DEFAULT_TARGET_BAUD_RATE = 460_800

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val READ_BUFFER_SIZE = 4096
        private const val MAX_READ_TIMEOUT_MS = 100L
        private const val MAX_SKIPPED_FRAMES = 4
        private const val SYNC_DRAIN_MS = 200L
        private const val SYNC_DRAIN_READ_MS = 20
        private const val RESET_PULSE_MS = 100L
        private const val RESET_SETTLE_MS = 50L
        private const val BAUD_SWITCH_DELAY_MS = 20L
    }
}
