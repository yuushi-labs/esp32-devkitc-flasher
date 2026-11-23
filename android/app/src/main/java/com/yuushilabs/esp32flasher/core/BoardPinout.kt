package com.yuushilabs.esp32flasher.core

/** Electrical role of a header pin on an ESP32-DevKitC board. */
enum class BoardPinKind { GPIO, POWER, GROUND, ENABLE, UART }

/** One physical header pin. [label] mirrors the silkscreen, [gpio] is set for GPIO pins. */
data class BoardPin(
    val label: String,
    val gpio: Int? = null,
    val kind: BoardPinKind = BoardPinKind.GPIO,
    val note: String? = null,
    /** Unique header position, so duplicate silkscreen labels (GND) stay distinct. */
    val id: String = "",
) {
    val displayLabel: String get() = gpio?.let { "GPIO$it" } ?: label

    val shortLabel: String get() = gpio?.toString() ?: label
}

/** The two header layouts the app can draw. */
enum class BoardVariant(val displayName: String, val left: List<BoardPin>, val right: List<BoardPin>) {
    DEVKIT_30("30ピン", PinLists.LEFT_30, PinLists.RIGHT_30),
    DEVKIT_38("38ピン", PinLists.LEFT_38, PinLists.RIGHT_38),
    ;

    val pins: List<BoardPin> get() = left + right

    fun pinForGpio(gpio: Int): BoardPin? = pins.firstOrNull { it.gpio == gpio }
}

/**
 * Header pinouts (top view, antenna at the top, USB at the bottom) for the 38-pin
 * ESP32-DevKitC V4 and the common 30-pin ESP32 DevKit board.
 */
private object PinLists {
    private fun gpio(number: Int, note: String? = null) = BoardPin("IO$number", number, BoardPinKind.GPIO, note)

    private fun List<BoardPin>.withIds(prefix: String): List<BoardPin> =
        mapIndexed { index, pin -> pin.copy(id = "$prefix$index") }

    val LEFT_38: List<BoardPin> = listOf(
        BoardPin("3V3", kind = BoardPinKind.POWER, note = "3.3V 出力"),
        BoardPin("EN", kind = BoardPinKind.ENABLE, note = "リセット (アクティブ LOW)"),
        gpio(36, "入力専用 / SENSOR_VP"),
        gpio(39, "入力専用 / SENSOR_VN"),
        gpio(34, "入力専用"),
        gpio(35, "入力専用"),
        gpio(32),
        gpio(33),
        gpio(25),
        gpio(26),
        gpio(27),
        gpio(14),
        gpio(12, "起動時にフラッシュ電圧を決定"),
        BoardPin("GND", kind = BoardPinKind.GROUND),
        gpio(13),
        gpio(9, "内蔵フラッシュ専用"),
        gpio(10, "内蔵フラッシュ専用"),
        gpio(11, "内蔵フラッシュ専用"),
        BoardPin("VIN", kind = BoardPinKind.POWER, note = "5V 入力"),
    ).withIds("L38-")

    val RIGHT_38: List<BoardPin> = listOf(
        BoardPin("GND", kind = BoardPinKind.GROUND),
        gpio(23),
        gpio(22),
        gpio(1, "UART0 TX"),
        gpio(3, "UART0 RX"),
        gpio(21),
        BoardPin("GND", kind = BoardPinKind.GROUND),
        gpio(19),
        gpio(18),
        gpio(5),
        gpio(17),
        gpio(16),
        gpio(4),
        gpio(0, "BOOT ボタン / 起動モード"),
        gpio(2, "起動時に接続状態を出力"),
        gpio(15),
        gpio(8, "内蔵フラッシュ専用"),
        gpio(7, "内蔵フラッシュ専用"),
        gpio(6, "内蔵フラッシュ専用"),
    ).withIds("R38-")

    val LEFT_30: List<BoardPin> = listOf(
        BoardPin("EN", kind = BoardPinKind.ENABLE, note = "リセット (アクティブ LOW)"),
        gpio(36, "入力専用 / SENSOR_VP"),
        gpio(39, "入力専用 / SENSOR_VN"),
        gpio(34, "入力専用"),
        gpio(35, "入力専用"),
        gpio(32),
        gpio(33),
        gpio(25),
        gpio(26),
        gpio(27),
        gpio(14),
        gpio(12, "起動時にフラッシュ電圧を決定"),
        gpio(13),
        BoardPin("GND", kind = BoardPinKind.GROUND),
        BoardPin("VIN", kind = BoardPinKind.POWER, note = "5V 入力"),
    ).withIds("L30-")

    val RIGHT_30: List<BoardPin> = listOf(
        gpio(23),
        gpio(22),
        gpio(1, "UART0 TX"),
        gpio(3, "UART0 RX"),
        gpio(21),
        gpio(19),
        gpio(18),
        gpio(5),
        gpio(17),
        gpio(16),
        gpio(4),
        gpio(2, "起動時に接続状態を出力"),
        gpio(15),
        BoardPin("GND", kind = BoardPinKind.GROUND),
        BoardPin("3V3", kind = BoardPinKind.POWER, note = "3.3V 出力"),
    ).withIds("R30-")
}

/** Maps manifest pin assignments onto physical header pins and exposes pin warnings. */
object BoardPinout {
    val FLASH_ONLY_GPIOS = 6..11
    val INPUT_ONLY_GPIOS = 34..39

    fun isFlashOnly(gpio: Int): Boolean = gpio in FLASH_ONLY_GPIOS

    fun isInputOnly(gpio: Int): Boolean = gpio in INPUT_ONLY_GPIOS

    fun warningsFor(pin: BoardPin): List<String> = buildList {
        val gpio = pin.gpio
        if (gpio != null && isFlashOnly(gpio)) {
            add("GPIO$gpio は内蔵 SPI フラッシュが使用するため外部接続できません。")
        }
        if (gpio != null && isInputOnly(gpio)) {
            add("GPIO$gpio は入力専用です。出力や内部プルアップは使用できません。")
        }
        when {
            pin.label == "3V3" -> add("3.3V 出力ピンです。ここへ 5V を入力すると破損します。")
            pin.label == "VIN" -> add("VIN は 5V 入力用です。3V3 や GPIO に 5V を入れないでください。")
            pin.kind == BoardPinKind.ENABLE -> add("EN はアクティブ LOW のリセットです。")
            else -> Unit
        }
        pin.note?.let { add(it) }
    }

    /**
     * Resolves which header pins must be highlighted for [assignments]. GPIO assignments map by
     * number; `GND` / `3V3` / `VIN` / `EN` assignments highlight the matching power pins.
     */
    fun resolveHighlights(
        variant: BoardVariant,
        assignments: List<PinAssignment>,
    ): Map<BoardPin, List<PinAssignment>> {
        val result = LinkedHashMap<BoardPin, MutableList<PinAssignment>>()
        assignments.forEach { assignment ->
            val pins = when {
                assignment.gpio != null -> listOfNotNull(variant.pinForGpio(assignment.gpio))
                else -> matchByLabel(variant, assignment.label)
            }
            pins.forEach { pin -> result.getOrPut(pin) { mutableListOf() }.add(assignment) }
        }
        return result
    }

    fun calloutText(assignments: List<PinAssignment>): String =
        assignments.joinToString(" / ") { it.label }

    private fun matchByLabel(variant: BoardVariant, label: String): List<BoardPin> {
        val normalized = label.uppercase().replace(" ", "")
        return when {
            normalized.contains("GND") || normalized.contains("GROUND") ->
                variant.pins.filter { it.kind == BoardPinKind.GROUND }

            normalized.contains("3V3") || normalized.contains("3.3") ->
                variant.pins.filter { it.label == "3V3" }

            normalized.contains("VIN") || normalized.contains("5V") ->
                variant.pins.filter { it.label == "VIN" }

            normalized == "EN" || normalized.contains("RESET") || normalized.contains("RST") ->
                variant.pins.filter { it.kind == BoardPinKind.ENABLE }

            else -> emptyList()
        }
    }
}
