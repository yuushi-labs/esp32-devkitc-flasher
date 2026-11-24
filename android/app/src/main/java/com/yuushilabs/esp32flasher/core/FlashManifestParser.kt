package com.yuushilabs.esp32flasher.core

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One flashable image: a file inside a release plus the offset it belongs to. */
data class FlashImage(
    val name: String,
    val fileName: String,
    val address: Long,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val downloadUrl: String? = null,
) {
    val addressLabel: String get() = HexAddressParser.format(address)
}

/**
 * A filesystem image documented in `flash-manifest.json`, e.g.
 * `{"type": "littlefs", "offset": "0x290000", "size": "0x160000", "asset": "littlefs.bin"}`.
 *
 * Firmware releases ship these next to the application so the app can flash the application and
 * the data partition together, or only the data partition.
 */
data class FilesystemImage(
    val type: String,
    val fileName: String,
    val address: Long,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val downloadUrl: String? = null,
) {
    val isLittleFs: Boolean get() = type.equals("littlefs", ignoreCase = true)
    val isSpiffs: Boolean get() = type.equals("spiffs", ignoreCase = true)
    val addressLabel: String get() = HexAddressParser.format(address)
}

/** Direction of a pin as documented by the firmware manifest. */
enum class PinDirection { INPUT, OUTPUT, BIDIRECTIONAL, UNKNOWN }

/** Active level of a pin as documented by the firmware manifest. */
enum class PinActiveLevel { HIGH, LOW, UNKNOWN }

/**
 * A GPIO documented in `flash-manifest.json` so the app can render a wiring guide, e.g.
 * `{"gpio": 4, "label": "LED", "direction": "output", "activeLevel": "high"}`.
 */
data class PinAssignment(
    val gpio: Int?,
    val label: String,
    val direction: PinDirection = PinDirection.UNKNOWN,
    val activeLevel: PinActiveLevel = PinActiveLevel.UNKNOWN,
    val note: String? = null,
) {
    val gpioLabel: String get() = gpio?.let { "GPIO$it" } ?: "—"
}

/** Parsed `flash-manifest.json`. */
data class FlashManifest(
    val version: String? = null,
    val chip: String? = null,
    val flashSize: String? = null,
    val notes: String? = null,
    val merged: FlashImage? = null,
    val parts: List<FlashImage> = emptyList(),
    val filesystems: List<FilesystemImage> = emptyList(),
    val pins: List<PinAssignment> = emptyList(),
    val wiringNotes: List<String> = emptyList(),
    val powerNote: String? = null,
) {
    val allImages: List<FlashImage> get() = listOfNotNull(merged) + parts

    /** Images to flash for this release: the firmware plus any filesystems it ships. */
    val flashableImages: List<FlashImage>
        get() = allImages + filesystems.map { fs ->
            FlashImage(
                name = fs.type,
                fileName = fs.fileName,
                address = fs.address,
                sizeBytes = fs.sizeBytes,
                sha256 = fs.sha256,
                downloadUrl = fs.downloadUrl,
            )
        }

    val isEmpty: Boolean get() = allImages.isEmpty() && filesystems.isEmpty()

    val hasWiringGuide: Boolean
        get() = pins.isNotEmpty() || wiringNotes.isNotEmpty() || !powerNote.isNullOrBlank()
}

/** An asset attached to a GitHub release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
    val digest: String? = null,
) {
    val isManifest: Boolean
        get() = name.equals("flash-manifest.json", ignoreCase = true) ||
            name.endsWith("/flash-manifest.json", ignoreCase = true)

    val isBinary: Boolean get() = name.endsWith(".bin", ignoreCase = true)

    val sha256: String?
        get() = digest
            ?.trim()
            ?.removePrefix("sha256:")
            ?.takeIf { it.isNotBlank() && it.length == 64 }
}

/** A GitHub release, reduced to what the flasher needs. */
data class FirmwareRelease(
    val tagName: String,
    val name: String,
    val body: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val publishedAt: String? = null,
    val htmlUrl: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val versionName: String get() = tagName.removePrefix("v").removePrefix("V")

    val displayName: String
        get() = name.takeIf { it.isNotBlank() && it != tagName } ?: tagName

    val manifestAsset: ReleaseAsset? get() = assets.firstOrNull { it.isManifest }

    val binaryAssets: List<ReleaseAsset> get() = assets.filter { it.isBinary }

    val primaryBinary: ReleaseAsset?
        get() = binaryAssets.firstOrNull { asset ->
            val lower = asset.name.lowercase()
            lower.contains("merged") || lower.contains("full") || lower.contains("combined")
        } ?: binaryAssets.firstOrNull { asset ->
            val lower = asset.name.lowercase()
            lower.contains("firmware") || lower.contains("app")
        } ?: binaryAssets.firstOrNull()

    val hasFlashableContent: Boolean get() = manifestAsset != null || binaryAssets.isNotEmpty()
}

/** Result of comparing a downloaded image against the manifest checksum. */
enum class Sha256Status {
    VERIFIED,
    MISMATCH,

    /** The manifest has no usable 64 character digest, so nothing could be compared. */
    NOT_AVAILABLE,
}

class ManifestFormatException(message: String) : Exception(message)

/**
 * Parses `flash-manifest.json` documents and GitHub release JSON. The parser is deliberately
 * tolerant: repositories in the wild use `file`/`filename`/`path`, string or numeric addresses,
 * and either a `merged` image or a list of `parts`.
 */
object FlashManifestParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val FILE_KEYS = arrayOf("file", "filename", "file_name", "path", "name")
    private val ADDRESS_KEYS = arrayOf("address", "offset", "flash_offset", "flash_address")
    private val SIZE_KEYS = arrayOf("size", "size_bytes", "bytes")
    private val SHA_KEYS = arrayOf("sha256", "sha256sum", "checksum", "hash")
    private val URL_KEYS = arrayOf("url", "download_url", "browser_download_url", "href")

    fun parseManifest(text: String): FlashManifest {
        val root = parseElement(text, "flash-manifest.json")
        val obj = root as? JsonObject
            ?: throw ManifestFormatException("flash-manifest.json のルートはオブジェクトである必要があります")

        val merged = parseMerged(obj)
        val parts = mutableListOf<FlashImage>()
        for (key in listOf("parts", "files", "images", "flash")) {
            val array = obj[key] as? JsonArray ?: continue
            array.forEachIndexed { index, element ->
                parts += parseImage(element, "part[$index]")
            }
        }
        val filesystems = parseFilesystems(obj)
        if (merged == null && parts.isEmpty() && filesystems.isEmpty()) {
            throw ManifestFormatException("flash-manifest.json に書き込み対象のイメージがありません")
        }
        return FlashManifest(
            version = obj.str("version", "fw_version", "firmware_version"),
            chip = obj.str("chip", "target", "mcu"),
            flashSize = obj.str("flash_size", "flashSize"),
            notes = obj.str("notes", "release_notes", "description"),
            merged = merged,
            parts = parts,
            filesystems = filesystems,
            pins = parsePins(obj),
            wiringNotes = parseWiringNotes(obj),
            powerNote = obj.str("powerNote", "power_note", "powerHint", "power_hint"),
        )
    }

    /**
     * Reads the `filesystems` array. Each entry names its type and asset; when the offset is
     * missing the ESP32 default layout is assumed for that type, so a manifest only has to say
     * `{"type": "littlefs", "asset": "littlefs.bin"}`.
     */
    private fun parseFilesystems(obj: JsonObject): List<FilesystemImage> {
        val array = obj["filesystems"] as? JsonArray
            ?: obj["filesystem"] as? JsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val type = entry.str("type", "fs", "kind", "filesystem")
                ?: if (entry.str(*FILE_KEYS)?.contains("spiffs", ignoreCase = true) == true) {
                    "spiffs"
                } else {
                    "littlefs"
                }
            val fileName = entry.str(*FILE_KEYS, "asset")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val address = entry.num(*ADDRESS_KEYS) ?: defaultFilesystemOffset(type)
            FilesystemImage(
                type = type.lowercase(),
                fileName = fileName,
                address = address,
                sizeBytes = entry.num(*SIZE_KEYS),
                sha256 = entry.str(*SHA_KEYS)?.normalizeSha(),
                downloadUrl = entry.str(*URL_KEYS),
            )
        }
    }

    /** ESP32 Arduino partition layout defaults for a filesystem image without an offset. */
    fun defaultFilesystemOffset(type: String): Long = when (type.lowercase()) {
        "spiffs", "littlefs", "fat" -> AddressPreset.LITTLEFS_DEFAULT.address
        else -> AddressPreset.LITTLEFS_DEFAULT.address
    }

    private fun parsePins(obj: JsonObject): List<PinAssignment> {
        val array = obj["pins"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val pin = element as? JsonObject ?: return@mapNotNull null
            val gpio = parseGpio(pin["gpio"] ?: pin["pin"] ?: pin["gpio_number"])
            val label = pin.str("label", "name", "signal", "function")
                ?: gpio?.let { "GPIO$it" }
                ?: return@mapNotNull null
            PinAssignment(
                gpio = gpio,
                label = label,
                direction = parseDirection(pin.str("direction", "mode", "type")),
                activeLevel = parseActiveLevel(pin.str("activeLevel", "active_level", "level", "active")),
                note = pin.str("note", "notes", "description", "comment"),
            )
        }
    }

    private fun parseWiringNotes(obj: JsonObject): List<String> {
        val element = obj["wiringNotes"] ?: obj["wiring_notes"] ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }

            is JsonPrimitive -> element.contentOrNull
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            else -> emptyList()
        }
    }

    private fun parseGpio(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.longOrHexOrNull()?.let { return it.toInt() }
        val digits = Regex("\\d+").find(primitive.contentOrNull.orEmpty())?.value
        return digits?.toIntOrNull()
    }

    private fun parseDirection(value: String?): PinDirection {
        val normalized = value?.trim()?.lowercase()?.replace('-', '_') ?: return PinDirection.UNKNOWN
        return when {
            normalized.startsWith("input") || normalized == "in" || normalized == "read" ->
                PinDirection.INPUT

            normalized.startsWith("output") || normalized == "out" || normalized == "write" ->
                PinDirection.OUTPUT

            normalized == "bidirectional" || normalized == "bidir" ||
                normalized == "io" || normalized == "inout" || normalized == "in_out" ->
                PinDirection.BIDIRECTIONAL

            else -> PinDirection.UNKNOWN
        }
    }

    private fun parseActiveLevel(value: String?): PinActiveLevel = when (value?.lowercase()) {
        "high", "active_high", "active-high", "activehigh", "1", "true" -> PinActiveLevel.HIGH
        "low", "active_low", "active-low", "activelow", "0", "false" -> PinActiveLevel.LOW
        else -> PinActiveLevel.UNKNOWN
    }

    private fun parseMerged(obj: JsonObject): FlashImage? {
        val element = obj["merged"] ?: obj["merged_image"] ?: return null
        return when (element) {
            is JsonObject -> parseImage(element, "merged", defaultAddress = AddressPreset.MERGED.address)
            is JsonPrimitive -> {
                val fileName = element.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
                FlashImage(
                    name = "merged",
                    fileName = fileName,
                    address = obj.num(*ADDRESS_KEYS) ?: AddressPreset.MERGED.address,
                    sizeBytes = obj.num(*SIZE_KEYS),
                    sha256 = obj.str(*SHA_KEYS)?.normalizeSha(),
                )
            }

            else -> null
        }
    }

    private fun parseImage(element: JsonElement, label: String, defaultAddress: Long? = null): FlashImage {
        val obj = element as? JsonObject
            ?: throw ManifestFormatException("$label はオブジェクトである必要があります")
        val fileName = obj.str(*FILE_KEYS)
            ?.takeIf { it.isNotBlank() }
            ?: throw ManifestFormatException("$label にファイル名がありません")
        // Writing at the wrong offset silently overwrites other regions, so an image whose
        // offset cannot be read or inferred is rejected instead of defaulting to 0x0000.
        val address = obj.num(*ADDRESS_KEYS) ?: defaultAddress ?: inferAddressOrNull(fileName)
            ?: throw ManifestFormatException(
                "$label ($fileName) の書き込みアドレスがなく、ファイル名からも判定できません",
            )
        return FlashImage(
            name = obj.str("name", "label", "id") ?: fileName,
            fileName = fileName,
            address = address,
            sizeBytes = obj.num(*SIZE_KEYS),
            sha256 = obj.str(*SHA_KEYS)?.normalizeSha(),
            downloadUrl = obj.str(*URL_KEYS),
        )
    }

    fun parseRelease(text: String): FirmwareRelease {
        val root = parseElement(text, "release")
        return when (root) {
            is JsonObject -> parseReleaseObject(root)
            is JsonArray -> root.firstOrNull()?.let {
                (it as? JsonObject)?.let(::parseReleaseObject)
            } ?: throw ManifestFormatException("Release が見つかりません")

            else -> throw ManifestFormatException("Release JSON の形式が不正です")
        }
    }

    fun parseReleases(text: String): List<FirmwareRelease> {
        val root = parseElement(text, "releases")
        return when (root) {
            is JsonArray -> root.mapNotNull { (it as? JsonObject)?.let(::parseReleaseObject) }
            is JsonObject -> listOf(parseReleaseObject(root))
            else -> throw ManifestFormatException("Release 一覧の JSON 形式が不正です")
        }
    }

    private fun parseReleaseObject(obj: JsonObject): FirmwareRelease {
        val tag = obj.str("tag_name", "tagName", "tag") ?: ""
        val assets = (obj["assets"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonObject)?.let(::parseAsset)
        } ?: emptyList()
        return FirmwareRelease(
            tagName = tag,
            name = obj.str("name") ?: tag,
            body = obj.str("body", "description"),
            prerelease = obj.bool("prerelease") ?: false,
            draft = obj.bool("draft") ?: false,
            publishedAt = obj.str("published_at", "publishedAt", "created_at"),
            htmlUrl = obj.str("html_url", "htmlUrl"),
            assets = assets,
        )
    }

    private fun parseAsset(obj: JsonObject): ReleaseAsset? {
        val name = obj.str("name") ?: return null
        val url = obj.str("browser_download_url", "download_url", "url") ?: return null
        return ReleaseAsset(
            name = name,
            downloadUrl = url,
            sizeBytes = obj.num("size") ?: 0L,
            digest = obj.str("digest"),
        )
    }

    /**
     * Guesses the flash offset from an asset name, so a release without a manifest still has a
     * sensible default.
     */
    fun inferAddress(fileName: String): Long = inferAddressOrNull(fileName) ?: AddressPreset.MERGED.address

    /** Like [inferAddress] but tells "not recognised" apart from `0x0`. */
    fun inferAddressOrNull(fileName: String): Long? {
        val lower = fileName.lowercase()
        return when {
            listOf("merged", "full", "combined", "all").any { lower.contains(it) } -> 0x0000L
            lower.contains("bootloader") -> 0x1000L
            lower.contains("partition") -> 0x8000L
            lower.contains("otadata") || lower.contains("boot_app0") -> 0xE000L
            lower.contains("app") || lower.contains("firmware") || lower.contains("main") -> 0x10000L
            else -> null
        }
    }

    /** The filesystem images of a release (littlefs / spiffs), in manifest order. */
    fun selectFilesystems(manifest: FlashManifest): List<FilesystemImage> = manifest.filesystems

    fun selectImages(manifest: FlashManifest, preferMerged: Boolean = true): List<FlashImage> {
        if (preferMerged) {
            manifest.merged?.let { return listOf(it) }
        }
        if (manifest.parts.isNotEmpty()) return manifest.parts
        return listOfNotNull(manifest.merged)
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /**
     * Compares [data] with the manifest digest and distinguishes "verified", "mismatch" and
     * "nothing to compare", so a typo or truncated digest cannot pass silently.
     */
    fun checkSha256(data: ByteArray, expected: String?): Sha256Status {
        val normalized = expected?.normalizeSha()?.takeIf { it.length == 64 }
            ?: return Sha256Status.NOT_AVAILABLE
        return if (sha256Hex(data).equals(normalized, ignoreCase = true)) {
            Sha256Status.VERIFIED
        } else {
            Sha256Status.MISMATCH
        }
    }

    fun verifySha256(data: ByteArray, expected: String?): Boolean =
        checkSha256(data, expected) != Sha256Status.MISMATCH

    private fun parseElement(text: String, label: String): JsonElement =
        try {
            json.parseToJsonElement(text)
        } catch (error: Exception) {
            throw ManifestFormatException("$label の JSON を解析できません: ${error.message}")
        }

    private fun JsonObject.str(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            val content = value.contentOrNull?.trim()
            if (!content.isNullOrEmpty()) return content
        }
        return null
    }

    private fun JsonObject.num(vararg keys: String): Long? {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            value.longOrHexOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonPrimitive.longOrHexOrNull(): Long? {
        if (isString) {
            val text = content.trim()
            return if (text.startsWith("0x", ignoreCase = true)) {
                text.substring(2).toLongOrNull(16)
            } else {
                text.toLongOrNull()
            }
        }
        return contentOrNull?.toLongOrNull()
    }

    private fun String.normalizeSha(): String? {
        val value = trim().removePrefix("sha256:").removePrefix("SHA256:").trim()
        return value.takeIf { it.isNotEmpty() }
    }
}
