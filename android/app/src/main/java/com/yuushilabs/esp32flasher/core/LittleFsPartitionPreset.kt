package com.yuushilabs.esp32flasher.core

/** The partition presets offered for LittleFS, mirroring the ESP32 Arduino partition tables. */
enum class LittleFsPartitionPreset(
    val label: String,
    val offset: Long,
    val size: Long,
    val note: String,
) {
    DEFAULT_4MB(
        label = "default.csv (4MB)",
        offset = 0x290000,
        size = 0x160000,
        note = "default 4MB with spiffs: 1,441,792 バイト",
    ),
    MIN_SPIFFS(
        label = "min_spiffs.csv (4MB)",
        offset = 0x3D0000,
        size = 0x20000,
        note = "app を最大にした構成: 131,072 バイト",
    ),
    CUSTOM(label = "カスタム", offset = 0L, size = 0L, note = "オフセットとサイズを手入力"),
}
