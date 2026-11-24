package com.yuushilabs.esp32flasher.core

/**
 * One file that should end up inside a generated LittleFS image.
 *
 * [path] is the path inside the filesystem, e.g. `index.html`, `www/app.js` or
 * `日本語/メモ.txt`. A leading `/` is accepted and ignored; `.` and `..` segments are rejected.
 */
data class LittleFsFile(val path: String, val data: ByteArray) {
    val normalizedPath: String get() = normalizePath(path)

    companion object {
        fun normalizePath(path: String): String = path
            .replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")

        fun hasParentSegment(path: String): Boolean =
            path.replace('\\', '/').split('/').any { it == ".." }
    }
}

/** A file path with its size, used to plan an image without holding its contents. */
data class LittleFsPlannedFile(val path: String, val size: Long)

/** What generating an image for a partition would cost. */
data class LittleFsImagePlan(
    val blockSize: Int,
    val blockCount: Int,
    val metadataBlocks: Int,
    val dataBlocks: Int,
    val fileCount: Int,
    val directoryCount: Int,
    val contentBytes: Long,
) {
    val requiredBlocks: Int get() = metadataBlocks + dataBlocks
    val usedBytes: Long get() = requiredBlocks.toLong() * blockSize
    val capacityBytes: Long get() = blockCount.toLong() * blockSize
    val usageRatio: Float
        get() = if (capacityBytes <= 0L) 0f else (usedBytes.toFloat() / capacityBytes).coerceIn(0f, 1f)
    val freeBytes: Long get() = (capacityBytes - usedBytes).coerceAtLeast(0L)
    val fits: Boolean get() = requiredBlocks in 1..blockCount
}

/**
 * Writes a littlefs v2.1 image (the on-disk format used by esp_littlefs, Arduino-ESP32
 * `LittleFS()` and PlatformIO `littlefs`) in pure Kotlin, so a phone can turn its own files into
 * a filesystem image without building the C library with the NDK.
 *
 * The layout mirrors what littlefs itself writes after a compaction:
 *
 * ```
 * block 0,1   root metadata pair: superblock entry, directory entries, and a hard tail when the
 *             directory needs more than one metadata block
 * block ...   one metadata pair per sub-directory, then the CTZ data blocks of larger files
 * ```
 *
 * Every metadata block holds a single commit: the revision count, the entry tags, and CRC tags
 * that pad the block to its end. littlefs reads that back exactly like its own `lfs_dir_compact`
 * output, and the image stays readable by `littlefs-python`.
 */
object LittleFsImageBuilder {
    const val DEFAULT_BLOCK_SIZE = 4096

    /** Files up to this size are stored inline in the metadata block, as littlefs does. */
    const val INLINE_MAX = 256

    /** littlefs allows 1022 bytes; mklittlefs writes 32, which every esp_littlefs build accepts. */
    const val DEFAULT_NAME_MAX = 32

    private const val REVISION_BYTES = 4
    private const val CRC_TAG_BYTES = 8
    private const val TAIL_TAG_BYTES = 12
    private const val RESERVED_TAIL_BYTES = CRC_TAG_BYTES + TAIL_TAG_BYTES
    private const val FILE_MAX = 0x7FFFFFFFL
    private const val ATTR_MAX = 1022L

    fun plan(
        files: List<LittleFsFile>,
        partitionSize: Int,
        blockSize: Int = DEFAULT_BLOCK_SIZE,
        nameMax: Int = DEFAULT_NAME_MAX,
        inlineMax: Int = INLINE_MAX,
    ): LittleFsImagePlan = planBySize(
        files = files.map { LittleFsPlannedFile(it.path, it.data.size.toLong()) },
        partitionSize = partitionSize,
        blockSize = blockSize,
        nameMax = nameMax,
        inlineMax = inlineMax,
    )

    /** Plans from path/size pairs, so the UI can show the usage bar before reading any content. */
    fun planBySize(
        files: List<LittleFsPlannedFile>,
        partitionSize: Int,
        blockSize: Int = DEFAULT_BLOCK_SIZE,
        nameMax: Int = DEFAULT_NAME_MAX,
        inlineMax: Int = INLINE_MAX,
    ): LittleFsImagePlan = layout(
        sources = files.map { FileContent(it.path, it.size, null) },
        partitionSize = partitionSize,
        blockSize = blockSize,
        nameMax = nameMax,
        inlineMax = inlineMax,
    ).plan

    fun build(
        files: List<LittleFsFile>,
        partitionSize: Int,
        blockSize: Int = DEFAULT_BLOCK_SIZE,
        nameMax: Int = DEFAULT_NAME_MAX,
        inlineMax: Int = INLINE_MAX,
    ): ByteArray {
        val layout = layout(
            sources = files.map { FileContent(it.path, it.data.size.toLong(), it.data) },
            partitionSize = partitionSize,
            blockSize = blockSize,
            nameMax = nameMax,
            inlineMax = inlineMax,
        )
        if (!layout.plan.fits) {
            throw LittleFsCapacityException(
                "ファイルがパーティションに収まりません " +
                    "(必要 ${layout.plan.usedBytes} バイト / 容量 ${layout.plan.capacityBytes} バイト)",
            )
        }
        return layout.render()
    }

    private fun layout(
        sources: List<FileContent>,
        partitionSize: Int,
        blockSize: Int,
        nameMax: Int,
        inlineMax: Int,
    ): Layout {
        require(blockSize >= 128 && blockSize % 16 == 0) { "ブロックサイズが不正です: $blockSize" }
        require(partitionSize >= blockSize * 2) {
            "パーティションサイズが小さすぎます (最低 ${blockSize * 2} バイト)"
        }
        require(partitionSize % blockSize == 0) {
            "パーティションサイズはブロックサイズ ($blockSize) の倍数である必要があります"
        }
        val blockCount = partitionSize / blockSize

        val root = DirNode("")
        for (file in sources) {
            if (LittleFsFile.hasParentSegment(file.path)) {
                throw LittleFsBuildException("パスに .. は使えません: ${file.path}")
            }
            val path = LittleFsFile.normalizePath(file.path)
            if (path.isEmpty()) throw LittleFsBuildException("ファイル名が空です")
            val segments = path.split('/')
            var node = root
            for (segment in segments.dropLast(1)) {
                node = node.dirs.getOrPut(segment) { DirNode(segment) }
            }
            val name = segments.last()
            if (name.toByteArray(Charsets.UTF_8).size > nameMax) {
                throw LittleFsBuildException("ファイル名が長すぎます ($nameMax バイトまで): $path")
            }
            if (node.files.containsKey(name)) {
                throw LittleFsBuildException("同じパスが複数あります: $path")
            }
            if (node.dirs.containsKey(name)) {
                throw LittleFsBuildException("ファイルとフォルダが同じ名前です: $path")
            }
            node.files[name] = file
        }
        validateNames(root, nameMax)

        val dirs = ArrayList<DirNode>()
        collectDirs(root, dirs)

        val encoded = LinkedHashMap<DirNode, List<Entry>>()
        for (dir in dirs) {
            encoded[dir] = encodeEntries(dir)
        }

        // Cut each directory into as many metadata blocks as it needs. Only the root's first block
        // reserves id 0 for the superblock entry.
        val chunks = LinkedHashMap<DirNode, List<List<Entry>>>()
        for (dir in dirs) {
            val headReserved = (if (dir === root) Encoder.SUPERBLOCK_DISK_SIZE else 0) +
                REVISION_BYTES + RESERVED_TAIL_BYTES
            chunks[dir] = splitIntoChunks(encoded.getValue(dir), blockSize, headReserved, inlineMax)
        }

        // Block numbers: the root pair is blocks 0/1; every further metadata block gets its own
        // two-block pair (littlefs swaps between the two copies while writing). CTZ data follows.
        var nextBlock = 2
        val pairs = LinkedHashMap<DirNode, List<BlockPair>>()
        for (dir in dirs) {
            val count = chunks.getValue(dir).size
            if (dir === root) {
                val list = ArrayList<BlockPair>(count)
                list += BlockPair(0, 1)
                for (index in 1 until count) {
                    list += BlockPair(nextBlock, nextBlock + 1)
                    nextBlock += 2
                }
                pairs[dir] = list
            } else {
                pairs[dir] = List(count) {
                    val pair = BlockPair(nextBlock, nextBlock + 1)
                    nextBlock += 2
                    pair
                }
            }
        }
        val ctzBlocks = LinkedHashMap<FileContent, List<Int>>()
        var dataBlocks = 0
        for (dir in dirs) {
            for (entry in encoded.getValue(dir)) {
                val file = entry.file ?: continue
                if (file.size <= inlineMax) continue
                val count = LittleFsFormat.ctzBlockCount(file.size, blockSize)
                ctzBlocks[file] = List(count) { nextBlock++ }
                dataBlocks += count
            }
        }

        val metadataBlocks = pairs.values.sumOf { it.size * 2 }
        val plan = LittleFsImagePlan(
            blockSize = blockSize,
            blockCount = blockCount,
            metadataBlocks = metadataBlocks,
            dataBlocks = dataBlocks,
            fileCount = sources.size,
            directoryCount = (dirs.size - 1).coerceAtLeast(0),
            contentBytes = sources.sumOf { it.size },
        )
        return Layout(
            plan = plan,
            partitionSize = partitionSize,
            blockSize = blockSize,
            nameMax = nameMax,
            inlineMax = inlineMax,
            root = root,
            dirs = dirs,
            encoded = encoded,
            chunks = chunks,
            pairs = pairs,
            ctzBlocks = ctzBlocks,
        )
    }

    private fun validateNames(node: DirNode, nameMax: Int) {
        for ((name, child) in node.dirs) {
            if (name.toByteArray(Charsets.UTF_8).size > nameMax) {
                throw LittleFsBuildException("フォルダ名が長すぎます ($nameMax バイトまで): $name")
            }
            validateNames(child, nameMax)
        }
    }

    private fun collectDirs(node: DirNode, out: MutableList<DirNode>) {
        out += node
        for (name in node.dirs.keys.sortedWith(::compareNames)) {
            collectDirs(node.dirs.getValue(name), out)
        }
    }

    private fun compareNames(left: String, right: String): Int =
        LittleFsFormat.compareNames(left, right)

    private fun encodeEntries(dir: DirNode): List<Entry> {
        val names = (dir.dirs.keys + dir.files.keys).sortedWith(::compareNames)
        val entries = ArrayList<Entry>(names.size)
        for (name in names) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            val child = dir.dirs[name]
            if (child != null) {
                entries += Entry(
                    tag = LittleFsFormat.makeTag(LittleFsFormat.TYPE_DIR, 0, bytes.size),
                    data = bytes,
                    directory = child,
                    file = null,
                )
            } else {
                entries += Entry(
                    tag = LittleFsFormat.makeTag(LittleFsFormat.TYPE_REG, 0, bytes.size),
                    data = bytes,
                    directory = null,
                    file = dir.files.getValue(name),
                )
            }
        }
        return entries
    }

    private fun splitIntoChunks(
        entries: List<Entry>,
        blockSize: Int,
        headReservedBytes: Int,
        inlineMax: Int,
    ): List<List<Entry>> {
        val chunks = ArrayList<List<Entry>>()
        var current = ArrayList<Entry>()
        var used = headReservedBytes
        for (entry in entries) {
            val entrySize = Encoder.diskSize(entry, inlineMax)
            if (used + entrySize > blockSize) {
                if (current.isEmpty()) {
                    throw LittleFsBuildException("1 件のエントリがメタデータブロックに収まりません")
                }
                chunks += current
                current = ArrayList()
                used = REVISION_BYTES + RESERVED_TAIL_BYTES
            }
            current += entry
            used += entrySize
        }
        if (current.isNotEmpty() || chunks.isEmpty()) {
            chunks += current
        }
        return chunks
    }

    /** One directory entry before its id and on-disk target are known. */
    internal data class Entry(
        val tag: Long,
        val data: ByteArray,
        val directory: DirNode?,
        val file: FileContent?,
    )

    private class Layout(
        val plan: LittleFsImagePlan,
        val partitionSize: Int,
        val blockSize: Int,
        val nameMax: Int,
        val inlineMax: Int,
        val root: DirNode,
        val dirs: List<DirNode>,
        val encoded: Map<DirNode, List<Entry>>,
        val chunks: Map<DirNode, List<List<Entry>>>,
        val pairs: Map<DirNode, List<BlockPair>>,
        val ctzBlocks: Map<FileContent, List<Int>>,
    ) {
        fun render(): ByteArray {
            val image = ByteArray(partitionSize) { 0xFF.toByte() }
            val writer = Encoder(image, blockSize, partitionSize / blockSize, inlineMax)

            for (dir in dirs) {
                for (entry in encoded.getValue(dir)) {
                    val file = entry.file ?: continue
                    val blocks = ctzBlocks[file] ?: continue
                    writer.writeCtzFile(blocks, file.data ?: ByteArray(0))
                }
            }

            for (dir in dirs) {
                val dirChunks = chunks.getValue(dir)
                val dirPairs = pairs.getValue(dir)
                for ((index, chunk) in dirChunks.withIndex()) {
                    val resolved = ArrayList<Encoder.ResolvedEntry>()
                    var nextId = 0
                    if (dir === root && index == 0) {
                        resolved += Encoder.ResolvedEntry(
                            tag = LittleFsFormat.makeTag(
                                LittleFsFormat.TYPE_SUPERBLOCK,
                                0,
                                LittleFsFormat.MAGIC.length,
                            ),
                            data = LittleFsFormat.MAGIC.toByteArray(Charsets.US_ASCII),
                            structTag = LittleFsFormat.makeTag(
                                LittleFsFormat.TYPE_INLINESTRUCT,
                                0,
                                LittleFsFormat.SUPERBLOCK_SIZE,
                            ),
                            structData = superblockBytes(),
                        )
                        nextId = 1
                    }
                    for (entry in chunk) {
                        resolved += resolve(entry, nextId)
                        nextId++
                    }
                    val tail = if (index + 1 < dirChunks.size) dirPairs[index + 1] else null
                    writer.writeMetadataBlock(dirPairs[index].head, resolved, tail)
                }
            }
            return image
        }

        private fun resolve(entry: Entry, id: Int): Encoder.ResolvedEntry {
            val nameTag = LittleFsFormat.makeTag(LittleFsFormat.type3(entry.tag), id, entry.data.size)
            val dir = entry.directory
            if (dir != null) {
                val pair = pairs[dir].orEmpty()
                if (pair.isEmpty()) {
                    throw LittleFsBuildException("フォルダ ${dir.name} のブロックがありません")
                }
                return Encoder.ResolvedEntry(
                    tag = nameTag,
                    data = entry.data,
                    structTag = LittleFsFormat.makeTag(LittleFsFormat.TYPE_DIRSTRUCT, id, 8),
                    structData = pairBytes(pair[0].head, pair[0].spare),
                )
            }
            val file = entry.file
                ?: return Encoder.ResolvedEntry(tag = nameTag, data = entry.data)
            if (file.size <= inlineMax) {
                return Encoder.ResolvedEntry(
                    tag = nameTag,
                    data = entry.data,
                    structTag = LittleFsFormat.makeTag(
                        LittleFsFormat.TYPE_INLINESTRUCT,
                        id,
                        file.size.toInt(),
                    ),
                    structData = file.data ?: ByteArray(0),
                )
            }
            val blocks = ctzBlocks[file].orEmpty()
            if (blocks.isEmpty()) throw LittleFsBuildException("${file.path} のデータブロックがありません")
            return Encoder.ResolvedEntry(
                tag = nameTag,
                data = entry.data,
                structTag = LittleFsFormat.makeTag(
                    LittleFsFormat.TYPE_CTZSTRUCT,
                    id,
                    8,
                ),
                structData = pairBytes(blocks.last(), file.size.toLong()),
            )
        }

        private fun pairBytes(first: Int, second: Int): ByteArray =
            pairBytes(first, second.toLong() and 0xFFFFFFFFL)

        private fun pairBytes(first: Int, second: Long): ByteArray {
            val bytes = ByteArray(8)
            LittleFsFormat.writeUInt32(bytes, 0, first.toLong() and 0xFFFFFFFFL)
            LittleFsFormat.writeUInt32(bytes, 4, second and 0xFFFFFFFFL)
            return bytes
        }

        private fun superblockBytes(): ByteArray {
            val bytes = ByteArray(LittleFsFormat.SUPERBLOCK_SIZE)
            LittleFsFormat.writeUInt32(bytes, 0, LittleFsFormat.DISK_VERSION.toLong())
            LittleFsFormat.writeUInt32(bytes, 4, blockSize.toLong())
            LittleFsFormat.writeUInt32(bytes, 8, (partitionSize / blockSize).toLong())
            LittleFsFormat.writeUInt32(bytes, 12, nameMax.toLong())
            LittleFsFormat.writeUInt32(bytes, 16, FILE_MAX)
            LittleFsFormat.writeUInt32(bytes, 20, ATTR_MAX.toLong())
            return bytes
        }
    }

    /** Writes metadata blocks and CTZ files into one image buffer. */
    private class Encoder(
        private val image: ByteArray,
        private val blockSize: Int,
        private val blockCount: Int,
        private val inlineMax: Int,
    ) {
        data class ResolvedEntry(
            val tag: Long,
            val data: ByteArray,
            val structTag: Long? = null,
            val structData: ByteArray? = null,
        )

        fun writeMetadataBlock(block: Int, entries: List<ResolvedEntry>, tail: BlockPair?) {
            requireBlock(block)
            val base = block * blockSize
            image.fill(0xFF.toByte(), base, base + blockSize)
            LittleFsFormat.writeUInt32(image, base, 0L) // revision count
            var offset = base + 4
            var crc = LittleFsFormat.crc(0xFFFFFFFF.toInt(), image, base, base + 4)
            var previous = 0xFFFFFFFFL

            fun writeTag(tag: Long, data: ByteArray) {
                val raw = (tag and 0x7FFFFFFFL) xor previous
                LittleFsFormat.writeUInt32BigEndian(image, offset, raw)
                crc = LittleFsFormat.crc(crc, image, offset, offset + 4)
                offset += 4
                if (data.isNotEmpty()) {
                    data.copyInto(image, offset)
                    crc = LittleFsFormat.crc(crc, image, offset, offset + data.size)
                    offset += data.size
                }
                previous = tag and 0x7FFFFFFFL
            }

            for (entry in entries) {
                writeTag(entry.tag, entry.data)
                val structTag = entry.structTag
                val structData = entry.structData
                if (structTag != null && structData != null) {
                    writeTag(structTag, structData)
                }
            }
            if (tail != null) {
                writeTag(
                    LittleFsFormat.makeTag(LittleFsFormat.TYPE_HARDTAIL, LittleFsFormat.ID_GLOBAL, 8),
                    pairBytes(tail.head, tail.spare),
                )
            }
            while (offset < base + blockSize) {
                val remaining = base + blockSize - offset
                if (remaining < CRC_TAG_BYTES) {
                    throw LittleFsBuildException("メタデータブロックの CRC 用の空きがありません")
                }
                val dataSize = (remaining - 4).coerceAtMost(LittleFsFormat.CRC_MAX_DATA)
                val tag = LittleFsFormat.makeTag(
                    LittleFsFormat.TYPE_CRC,
                    LittleFsFormat.ID_GLOBAL,
                    dataSize,
                )
                val raw = (tag and 0x7FFFFFFFL) xor previous
                LittleFsFormat.writeUInt32BigEndian(image, offset, raw)
                crc = LittleFsFormat.crc(crc, image, offset, offset + 4)
                LittleFsFormat.writeUInt32(image, offset + 4, crc.toLong() and 0xFFFFFFFFL)
                previous = tag and 0x7FFFFFFFL
                crc = 0xFFFFFFFF.toInt()
                offset += 4 + dataSize
            }
        }

        fun writeCtzFile(blocks: List<Int>, data: ByteArray) {
            var position = 0
            for ((index, block) in blocks.withIndex()) {
                requireBlock(block)
                val base = block * blockSize
                image.fill(0xFF.toByte(), base, base + blockSize)
                if (index > 0) {
                    val skips = Integer.numberOfTrailingZeros(index) + 1
                    for (skip in 0 until skips) {
                        val target = blocks[index - (1 shl skip)]
                        LittleFsFormat.writeUInt32(image, base + 4 * skip, target.toLong())
                    }
                }
                val capacity = LittleFsFormat.ctzDataCapacity(index, blockSize)
                val length = minOf(capacity, data.size - position)
                if (length > 0) {
                    data.copyInto(
                        image,
                        base + LittleFsFormat.ctzDataOffset(index, blockSize),
                        position,
                        position + length,
                    )
                    position += length
                }
            }
            if (position != data.size) {
                throw LittleFsBuildException("CTZ ファイルの書き込みが途中で終わりました")
            }
        }

        private fun requireBlock(block: Int) {
            if (block < 0 || block >= blockCount) {
                throw LittleFsBuildException("ブロック番号が範囲外です: $block")
            }
        }

        private fun pairBytes(first: Int, second: Int): ByteArray =
            pairBytes(first, second.toLong() and 0xFFFFFFFFL)

        private fun pairBytes(first: Int, second: Long): ByteArray {
            val bytes = ByteArray(8)
            LittleFsFormat.writeUInt32(bytes, 0, first.toLong() and 0xFFFFFFFFL)
            LittleFsFormat.writeUInt32(bytes, 4, second and 0xFFFFFFFFL)
            return bytes
        }

        companion object {
            val SUPERBLOCK_DISK_SIZE: Int = 4 + LittleFsFormat.MAGIC.length + 4 + LittleFsFormat.SUPERBLOCK_SIZE

            fun diskSize(entry: Entry, inlineMax: Int): Int {
                val nameSize = 4 + entry.data.size
                return when {
                    entry.directory != null -> nameSize + 12
                    entry.file == null -> nameSize
                    entry.file.size <= inlineMax -> nameSize + 4 + entry.file.size.toInt()
                    else -> nameSize + 12
                }
            }
        }
    }
}

/** The two blocks of one metadata pair: the live copy and its spare. */
internal data class BlockPair(val head: Int, val spare: Int)

/** A directory of the file tree the builder lays out. */
internal class DirNode(val name: String) {
    val dirs = LinkedHashMap<String, DirNode>()
    val files = LinkedHashMap<String, FileContent>()
}

/** One file of the tree: its size is always known, its bytes only while building. */
internal data class FileContent(val path: String, val size: Long, val data: ByteArray?)

class LittleFsBuildException(message: String) : Exception(message)

class LittleFsCapacityException(message: String) : Exception(message)
