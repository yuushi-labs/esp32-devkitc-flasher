package com.yuushilabs.esp32flasher.core

/** One file or folder inside a littlefs image. */
data class LittleFsEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
) {
    val parentPath: String get() = path.substringBeforeLast('/', "")
}

/**
 * Reads a littlefs v2.1 image (as written by [LittleFsImageBuilder], `littlefs-python`, mklittlefs
 * or esp_littlefs itself) with pure Kotlin.
 *
 * Only the read path is implemented, but the metadata log is replayed the way littlefs does it:
 * a metadata block can contain several commits, later commits supersede earlier entries, and
 * create/delete splice tags renumber the ids of the entries before them.
 */
class LittleFsReader(private val image: ByteArray) {
    data class Superblock(
        val version: Long,
        val blockSize: Int,
        val blockCount: Int,
        val nameMax: Long,
        val fileMax: Long,
        val attrMax: Long,
    ) {
        val versionLabel: String
            get() = "v%d.%d".format((version shr 16) and 0xFFFF, version and 0xFFFF)
    }

    private data class BlockPair(val head: Int, val spare: Int)

    private data class TagRef(val offset: Int, val tag: Long) {
        val dataOffset: Int get() = offset + 4

        /** Bytes that really follow the tag word: a delete marker (size 0x3ff) carries none. */
        val dataSize: Int get() = LittleFsFormat.diskSize(tag) - 4
    }

    private data class ParsedBlock(
        val revision: Long,
        val valid: Boolean,
        val tags: List<TagRef>,
        val tail: BlockPair?,
        val split: Boolean,
        val superblock: Superblock?,
    )

    private class EntryState(val id: Int) {
        var name: String? = null
        var type: Int = 0
        var inline: ByteArray? = null
        var ctzHead: Int = -1
        var ctzSize: Long = 0
        var dirPair: BlockPair? = null

        // A later struct tag supersedes the earlier one, so switching between inline data and a CTZ
        // skip-list has to clear the previous form.
        fun applyInline(data: ByteArray) {
            inline = data
            ctzHead = -1
            ctzSize = 0
        }

        fun applyCtz(head: Int, size: Long) {
            inline = null
            ctzHead = head
            ctzSize = size
        }

        fun applyDirectory(pair: BlockPair) {
            inline = null
            ctzHead = -1
            ctzSize = 0
            dirPair = pair
        }
    }

    val superblock: Superblock
    private val blockSize: Int
    private val rootPair: BlockPair

    init {
        if (image.size < 64) {
            throw LittleFsFormatException("イメージが小さすぎます (${image.size} バイト)")
        }
        // The superblock lives in the metadata pair on blocks 0 and 1; either copy may be the one
        // that survived, exactly like the reference implementation reads them.
        val found = readSuperblockAt(0) ?: readSuperblockAt(superblockBlockSize())
            ?: throw LittleFsFormatException("littlefs のスーパーブロックが見つかりません")
        val major = (found.version shr 16) and 0xFFFF
        if (major != 2L) {
            throw LittleFsFormatException(
                "未対応の littlefs バージョンです: 0x%08X".format(found.version),
            )
        }
        if (found.blockSize < 128 || found.blockSize > image.size ||
            image.size % found.blockSize != 0
        ) {
            throw LittleFsFormatException("ブロックサイズが不正です: ${found.blockSize}")
        }
        blockSize = found.blockSize
        superblock = found
        rootPair = locateRoot()
    }

    /** Block size candidate for the second copy: the LFS block size is a power of two. */
    private fun superblockBlockSize(): Int {
        var size = 0x10000
        while (size > 128 && image.size % size != 0) {
            size /= 2
        }
        return size
    }

    /** Walks the superblock metadata chain; the last pair that carries a superblock is the root. */
    private fun locateRoot(): BlockPair {
        var pair = BlockPair(0, 1)
        var root: BlockPair? = null
        val visited = HashSet<BlockPair>()
        while (visited.size <= MAX_PAIRS) {
            if (!visited.add(pair)) break
            val block = runCatching { readBlock(pair) }.getOrNull() ?: break
            if (block.superblock != null) root = pair
            val tail = block.tail ?: break
            if (tail.head < 0 || tail.spare < 0) break
            if (tail.head >= superblock.blockCount || tail.spare >= superblock.blockCount) break
            if (tail.head >= image.size / blockSize || tail.spare >= image.size / blockSize) break
            pair = tail
        }
        return root ?: throw LittleFsFormatException("littlefs のスーパーブロックをたどれませんでした")
    }

    /** Every file and folder in the image; folders come before their contents. */
    fun list(): List<LittleFsEntry> {
        val out = ArrayList<LittleFsEntry>()
        readDirectory(rootPair, "", out, HashSet())
        return out
    }

    fun files(): List<LittleFsEntry> = list().filter { !it.isDirectory }

    fun totalBytes(): Long = files().sumOf { it.size }

    fun read(path: String): ByteArray {
        val entry = files().firstOrNull { it.path == path.trimStart('/') }
            ?: throw LittleFsFormatException("ファイルが見つかりません: $path")
        return read(entry)
    }

    fun read(entry: LittleFsEntry): ByteArray {
        if (entry.isDirectory) throw LittleFsFormatException("フォルダは読み出せません: ${entry.path}")
        val state = findEntry(entry.path)
            ?: throw LittleFsFormatException("ファイルが見つかりません: ${entry.path}")
        val inline = state.inline
        if (inline != null) return inline
        if (state.ctzHead >= 0) return readCtz(state.ctzHead, state.ctzSize)
        if (state.ctzSize == 0L) return ByteArray(0)
        throw LittleFsFormatException("${entry.path} のデータ形式を解釈できません")
    }

    private fun readDirectory(
        start: BlockPair,
        parentPath: String,
        out: MutableList<LittleFsEntry>,
        visited: MutableSet<BlockPair>,
    ) {
        var pair = start
        var guard = 0
        while (true) {
            if (!visited.add(pair)) {
                throw LittleFsFormatException("メタデータのリンクが循環しています")
            }
            val block = readBlock(pair)
            for (state in readEntries(block)) {
                val name = state.name ?: continue
                if (state.type == LittleFsFormat.TYPE_SUPERBLOCK) continue
                val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                if (state.type == LittleFsFormat.TYPE_DIR) {
                    out += LittleFsEntry(path = path, name = name, isDirectory = true, size = 0)
                    val childPair = state.dirPair
                    if (childPair != null && childPair.head < image.size / blockSize) {
                        readDirectory(childPair, path, out, visited)
                    }
                } else {
                    val size = when {
                        state.inline != null -> state.inline!!.size.toLong()
                        state.ctzHead >= 0 -> state.ctzSize
                        else -> 0L
                    }
                    out += LittleFsEntry(path = path, name = name, isDirectory = false, size = size)
                }
            }
            // A hard tail (split) continues this directory; a soft tail only links metadata
            // pairs in the filesystem-wide thread, so the listing stops here.
            if (!block.split) break
            val tail = block.tail ?: break
            pair = tail
            guard++
            if (guard > MAX_PAIRS) break
        }
    }

    private fun findEntry(path: String): EntryState? {
        val segments = path.trimStart('/').split('/')
        var pair = rootPair
        var state: EntryState? = null
        for ((index, segment) in segments.withIndex()) {
            state = findInDirectory(pair, segment) ?: return null
            if (index < segments.size - 1) {
                if (state.type != LittleFsFormat.TYPE_DIR) return null
                pair = state.dirPair ?: return null
            }
        }
        return state
    }

    /** Looks a name up across every metadata block of one directory (the hard tail chain). */
    private fun findInDirectory(start: BlockPair, name: String): EntryState? {
        var pair = start
        var guard = 0
        while (true) {
            val block = readBlock(pair)
            val found = readEntries(block).firstOrNull {
                it.name == name && it.type != LittleFsFormat.TYPE_SUPERBLOCK
            }
            if (found != null) return found
            if (!block.split) return null
            pair = block.tail ?: return null
            guard++
            if (guard > MAX_PAIRS) return null
        }
    }

    /** Replays the metadata log of a directory pair and returns its live entries, ordered by id. */
    private fun readEntries(block: ParsedBlock): List<EntryState> {
        val entries = HashMap<Int, EntryState>()
        var count = 0

        fun shift(from: Int, delta: Int) {
            if (entries.isEmpty()) return
            val moved = HashMap<Int, EntryState>(entries.size)
            for ((id, state) in entries) {
                val target = if (id >= from) id + delta else id
                if (target >= 0) moved[target] = state
            }
            entries.clear()
            entries.putAll(moved)
        }

        fun stateFor(id: Int): EntryState = entries.getOrPut(id) { EntryState(id) }

        for (ref in block.tags) {
            val tag = ref.tag
            val id = LittleFsFormat.id(tag)
            when {
                LittleFsFormat.type3(tag) == LittleFsFormat.TYPE_CREATE -> {
                    shift(id, 1)
                    count += 1
                }

                LittleFsFormat.type3(tag) == LittleFsFormat.TYPE_DELETE -> {
                    // Remove the target before compacting the ids, or the entry that moves into its
                    // slot would be deleted instead.
                    entries.remove(id)
                    shift(id + 1, -1)
                    count = (count - 1).coerceAtLeast(0)
                }

                LittleFsFormat.type1(tag) == TYPE1_NAME -> {
                    val bytes = image.copyOfRange(ref.dataOffset, ref.dataOffset + ref.dataSize)
                    val state = stateFor(id)
                    state.name = String(bytes, Charsets.UTF_8)
                    state.type = LittleFsFormat.type3(tag)
                    if (count <= id) count = id + 1
                }

                LittleFsFormat.type1(tag) == TYPE1_STRUCT -> {
                    val state = stateFor(id)
                    when (LittleFsFormat.type3(tag)) {
                        LittleFsFormat.TYPE_INLINESTRUCT -> {
                            state.applyInline(
                                image.copyOfRange(ref.dataOffset, ref.dataOffset + ref.dataSize),
                            )
                        }

                        LittleFsFormat.TYPE_DIRSTRUCT -> if (ref.dataSize >= 8) {
                            state.applyDirectory(
                                BlockPair(
                                    LittleFsFormat.readUInt32(image, ref.dataOffset).toInt(),
                                    LittleFsFormat.readUInt32(image, ref.dataOffset + 4).toInt(),
                                ),
                            )
                        }

                        LittleFsFormat.TYPE_CTZSTRUCT -> if (ref.dataSize >= 8) {
                            state.applyCtz(
                                LittleFsFormat.readUInt32(image, ref.dataOffset).toInt(),
                                LittleFsFormat.readUInt32(image, ref.dataOffset + 4),
                            )
                        }
                    }
                }
            }
        }
        return (0 until count).mapNotNull { entries[it] }
    }

    /** Rejects a block number that cannot exist in this image before it is turned into an offset. */
    private fun requireBlock(block: Int, what: String): Int {
        val blocks = image.size / blockSize
        if (block < 0 || block >= blocks) {
            throw LittleFsFormatException("$what がイメージの外を指しています: block=$block")
        }
        return block
    }

    private fun blockBase(block: Int, what: String): Int = requireBlock(block, what) * blockSize

    /** Picks the newest copy of a metadata pair that holds at least one valid commit. */
    private fun readBlock(pair: BlockPair): ParsedBlock {
        requireBlock(pair.head, "メタデータ")
        val candidates = listOf(pair.head, pair.spare)
            .mapNotNull { block -> runCatching { parseBlock(block) }.getOrNull() }
            .filter { it.valid }
        if (candidates.isEmpty()) {
            throw LittleFsFormatException("メタデータブロックを読めませんでした (block ${pair.head})")
        }
        return candidates.reduce { best, next -> if (next.revision > best.revision) next else best }
    }

    private fun parseBlock(block: Int): ParsedBlock? {
        if (block < 0 || block >= image.size / blockSize) return null
        val base = block * blockSize
        val revision = LittleFsFormat.readUInt32(image, base)
        if (revision == 0xFFFFFFFFL) return null

        var offset = base
        var previous = 0xFFFFFFFFL
        var crc = LittleFsFormat.crc(0xFFFFFFFF.toInt(), image, base, base + 4)
        var validCommitEnd = -1
        val tags = ArrayList<TagRef>()

        while (true) {
            offset += LittleFsFormat.diskSize(previous)
            val relative = offset - base
            if (relative + 4 > blockSize) break
            val raw = LittleFsFormat.readUInt32BigEndian(image, offset)
            crc = LittleFsFormat.crc(crc, image, offset, offset + 4)
            val tag = raw xor previous
            if (!LittleFsFormat.isValid(tag)) break
            if (relative.toLong() + LittleFsFormat.diskSize(tag) > blockSize) break

            if (LittleFsFormat.type3(tag) and 0x7FE == LittleFsFormat.TYPE_CRC) {
                if (LittleFsFormat.size(tag) < 4) break
                val stored = LittleFsFormat.readUInt32(image, offset + 4)
                if ((stored.toInt()) != crc) break
                // The CRC tag also carries the valid bit expected from the next commit.
                previous = (tag and 0x7FFFFFFFL) xor
                    ((LittleFsFormat.chunk(tag) and 1).toLong() shl 31)
                crc = 0xFFFFFFFF.toInt()
                validCommitEnd = tags.size
                continue
            }

            crc = LittleFsFormat.crc(
                crc,
                image,
                offset + 4,
                offset + 4 + (LittleFsFormat.diskSize(tag) - 4),
            )
            previous = tag and 0x7FFFFFFFL
            tags += TagRef(offset, tag)
        }

        if (validCommitEnd < 0) {
            return ParsedBlock(revision, false, emptyList(), null, false, null)
        }
        val live = tags.subList(0, validCommitEnd).toList()
        var tail: BlockPair? = null
        var split = false
        var superblock: Superblock? = null
        for (ref in live) {
            val tag = ref.tag
            if (LittleFsFormat.type1(tag) == TYPE1_TAIL &&
                LittleFsFormat.size(tag) >= 8
            ) {
                tail = BlockPair(
                    LittleFsFormat.readUInt32(image, ref.dataOffset).toInt(),
                    LittleFsFormat.readUInt32(image, ref.dataOffset + 4).toInt(),
                )
                split = (LittleFsFormat.chunk(tag) and 1) == 1
            }
            if (LittleFsFormat.type3(tag) == LittleFsFormat.TYPE_SUPERBLOCK && superblock == null) {
                superblock = readSuperblockAt(base)
            }
        }
        return ParsedBlock(revision, true, live, tail, split, superblock)
    }

    private fun readSuperblockAt(base: Int): Superblock? {
        if (base < 0 || base + 44 > image.size) return null
        val magic = runCatching {
            String(image, base + 8, LittleFsFormat.MAGIC.length, Charsets.US_ASCII)
        }.getOrNull() ?: return null
        if (magic != LittleFsFormat.MAGIC) return null
        val blockSizeValue = LittleFsFormat.readUInt32(image, base + 24).toInt()
        if (blockSizeValue < 128 || blockSizeValue > image.size) return null
        return Superblock(
            version = LittleFsFormat.readUInt32(image, base + 20),
            blockSize = LittleFsFormat.readUInt32(image, base + 24).toInt(),
            blockCount = LittleFsFormat.readUInt32(image, base + 28).toInt(),
            nameMax = LittleFsFormat.readUInt32(image, base + 32),
            fileMax = LittleFsFormat.readUInt32(image, base + 36),
            attrMax = LittleFsFormat.readUInt32(image, base + 40),
        )
    }

    private fun readCtz(head: Int, size: Long): ByteArray {
        if (size <= 0L) return ByteArray(0)
        // The declared size must be something the image can actually hold, otherwise a corrupt
        // entry would allocate an enormous array before any block check runs.
        val capacity = image.size.toLong()
        if (size > capacity || size > Int.MAX_VALUE) {
            throw LittleFsFormatException(
                "ファイルサイズが不正です: $size バイト (イメージ $capacity バイト)",
            )
        }
        requireBlock(head, "CTZ の先頭")
        val out = ByteArray(size.toInt())
        var written = 0
        val lastIndex = LittleFsFormat.ctzIndex(size - 1, blockSize)
        for (index in 0..lastIndex) {
            val position = LittleFsFormat.ctzFileOffset(index, blockSize)
            if (position >= size) break
            val capacity = LittleFsFormat.ctzDataCapacity(index, blockSize)
            val length = minOf(capacity.toLong(), size - position).toInt()
            if (length <= 0) break
            val block = findCtzBlock(head, lastIndex, position)
            val base = blockBase(block, "CTZ ブロック")
            val from = base + LittleFsFormat.ctzDataOffset(index, blockSize)
            image.copyInto(out, written, from, from + length)
            written += length
        }
        return if (written == size.toInt()) out else out.copyOf(written)
    }

    /** `lfs_ctz_find`: follows the skip-list from its head down to the block holding [position]. */
    private fun findCtzBlock(head: Int, lastIndex: Int, position: Long): Int {
        var current = lastIndex
        var block = head
        val target = LittleFsFormat.ctzIndex(position, blockSize)
        var guard = 0
        while (current > target) {
            val skip = minOf(
                LittleFsFormat.npw2(current - target + 1) - 1,
                Integer.numberOfTrailingZeros(current),
            )
            val base = blockBase(block, "CTZ のポインタ")
            if (base + 4 * skip + 4 > image.size) {
                throw LittleFsFormatException("CTZ のポインタがイメージの外にあります: $block")
            }
            val next = requireBlock(
                LittleFsFormat.readUInt32(image, base + 4 * skip).toInt(),
                "CTZ のリンク",
            )
            block = next
            current -= 1 shl skip
            guard++
            if (guard > MAX_PAIRS) throw LittleFsFormatException("CTZ のリンクが循環しています")
        }
        return block
    }

    companion object {
        private const val MAX_PAIRS = 4096
        private const val TYPE1_NAME = 0
        private const val TYPE1_STRUCT = 2
        private const val TYPE1_TAIL = 6

        fun isLittleFs(image: ByteArray): Boolean {
            if (image.size < 64) return false
            fun hasMagic(base: Int): Boolean =
                base + 8 + LittleFsFormat.MAGIC.length <= image.size &&
                    String(image, base + 8, LittleFsFormat.MAGIC.length, Charsets.US_ASCII) ==
                    LittleFsFormat.MAGIC
            if (hasMagic(0)) return true
            // The second copy of the superblock pair can be the one that survived.
            var candidate = 0x10000
            while (candidate > 128 && image.size % candidate != 0) candidate /= 2
            return hasMagic(candidate)
        }
    }
}
