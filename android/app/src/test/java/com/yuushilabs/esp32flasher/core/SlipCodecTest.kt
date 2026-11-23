package com.yuushilabs.esp32flasher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SlipCodecTest {
    @Test
    fun `encode wraps payload in END delimiters`() {
        val encoded = SlipCodec.encode(byteArrayOf(0x01, 0x02, 0x03))
        assertEquals(SlipCodec.END, encoded.first().toInt() and 0xFF)
        assertEquals(SlipCodec.END, encoded.last().toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), encoded.copyOfRange(1, encoded.size - 1))
    }

    @Test
    fun `encode escapes END and ESC bytes`() {
        val encoded = SlipCodec.encode(byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x42))
        assertArrayEquals(
            byteArrayOf(
                SlipCodec.END.toByte(),
                SlipCodec.ESC.toByte(),
                SlipCodec.ESC_END.toByte(),
                SlipCodec.ESC.toByte(),
                SlipCodec.ESC_ESC.toByte(),
                0x42,
                SlipCodec.END.toByte(),
            ),
            encoded,
        )
    }

    @Test
    fun `decode restores escaped payload`() {
        val payload = byteArrayOf(0x00, 0xC0.toByte(), 0xDB.toByte(), 0xFF.toByte(), 0x7F)
        assertArrayEquals(payload, SlipCodec.decode(SlipCodec.encode(payload)))
    }

    @Test
    fun `decode accepts frames with surrounding delimiters`() {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20)
        val framed = byteArrayOf(SlipCodec.END.toByte()) + SlipCodec.encode(payload)
        assertArrayEquals(payload, SlipCodec.decode(framed))
    }

    @Test
    fun `decode rejects dangling escape`() {
        assertThrows(IllegalArgumentException::class.java) {
            SlipCodec.decode(byteArrayOf(0x01, SlipCodec.ESC.toByte()))
        }
    }

    @Test
    fun `decode rejects unknown escape`() {
        assertThrows(SlipFormatException::class.java) {
            SlipCodec.decode(byteArrayOf(SlipCodec.ESC.toByte(), 0x11))
        }
    }

    @Test
    fun `checksum sums bytes modulo 256`() {
        assertEquals(0x0F, SlipCodec.checksum(byteArrayOf(0x01, 0x02, 0x0C)))
        assertEquals(0x00, SlipCodec.checksum(byteArrayOf(0x80.toByte(), 0x80.toByte())))
        assertEquals(0, SlipCodec.checksum(ByteArray(0)))
    }

    @Test
    fun `streaming decoder emits frames split across chunks`() {
        val decoder = SlipDecoder()
        val frame = SlipCodec.encode(byteArrayOf(0x01, 0x02, 0x03))
        val first = decoder.feed(frame, 0, 3)
        assertTrue(first.isEmpty())
        val second = decoder.feed(frame, 3, frame.size - 3)
        assertEquals(1, second.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), second.first())
    }

    @Test
    fun `streaming decoder emits multiple frames from one chunk`() {
        val decoder = SlipDecoder()
        val first = SlipCodec.encode(byteArrayOf(0x11))
        val second = SlipCodec.encode(byteArrayOf(0x22, 0x33))
        val frames = decoder.feed(first + second)
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(0x11), frames[0])
        assertArrayEquals(byteArrayOf(0x22, 0x33), frames[1])
    }

    @Test
    fun `streaming decoder ignores empty frames`() {
        val decoder = SlipDecoder()
        val frames = decoder.feed(byteArrayOf(SlipCodec.END.toByte(), SlipCodec.END.toByte()))
        assertTrue(frames.isEmpty())
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun `streaming decoder drops malformed escape and recovers`() {
        val decoder = SlipDecoder()
        val broken = byteArrayOf(0x01, SlipCodec.ESC.toByte(), 0x33, SlipCodec.END.toByte())
        assertTrue(decoder.feed(broken).isEmpty())
        val recovered = decoder.feed(SlipCodec.encode(byteArrayOf(0x42)))
        assertEquals(1, recovered.size)
        assertArrayEquals(byteArrayOf(0x42), recovered.first())
    }

    @Test
    fun `streaming decoder drops oversized frames`() {
        val decoder = SlipDecoder(maxFrameSize = 4)
        val frames = decoder.feed(SlipCodec.encode(ByteArray(8) { 0x01 }))
        assertTrue(frames.isEmpty())
        assertArrayEquals(byteArrayOf(0x05), decoder.feed(SlipCodec.encode(byteArrayOf(0x05))).first())
    }
}
