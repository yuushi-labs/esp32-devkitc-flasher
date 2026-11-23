package com.yuushilabs.esp32flasher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexAddressParserTest {
    @Test
    fun `parses prefixed hexadecimal addresses`() {
        assertEquals(0x0L, HexAddressParser.parse("0x0000").getOrThrow())
        assertEquals(0x10000L, HexAddressParser.parse("0x10000").getOrThrow())
        assertEquals(0x1000L, HexAddressParser.parse("0X1000").getOrThrow())
        assertEquals(0xFFFFFFL, HexAddressParser.parse("0xffffff").getOrThrow())
    }

    @Test
    fun `bare digits are hexadecimal because flash offsets are hexadecimal`() {
        assertEquals(0x10000L, HexAddressParser.parse("10000").getOrThrow())
        assertEquals(0x0L, HexAddressParser.parse("0").getOrThrow())
        assertEquals(0xABC0L, HexAddressParser.parse("abc0").getOrThrow())
    }

    @Test
    fun `decimal prefix is supported`() {
        assertEquals(4096L, HexAddressParser.parse("0d4096").getOrThrow())
        assertEquals(65536L, HexAddressParser.parse("0D65536").getOrThrow())
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(0x10000L, HexAddressParser.parse("  0x10000 ").getOrThrow())
    }

    @Test
    fun `rejects empty and malformed input`() {
        assertTrue(HexAddressParser.parse("").isFailure)
        assertTrue(HexAddressParser.parse("   ").isFailure)
        assertTrue(HexAddressParser.parse("0x").isFailure)
        assertTrue(HexAddressParser.parse("0d").isFailure)
        assertTrue(HexAddressParser.parse("0xZZ").isFailure)
        assertTrue(HexAddressParser.parse("-0x10").isFailure)
        assertTrue(HexAddressParser.parse("0d12AB").isFailure)
    }

    @Test
    fun `rejects addresses outside the 16 MB flash window`() {
        assertTrue(HexAddressParser.parse("0x1000001").isFailure)
        assertTrue(HexAddressParser.parse("0xFFFFFFFF").isFailure)
        assertFalse(HexAddressParser.parse("0xFFFFFF").isFailure)
    }

    @Test
    fun `failure carries a japanese explanation`() {
        val message = HexAddressParser.parse("nope").exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("アドレス"))
    }

    @Test
    fun `format pads to four hex digits`() {
        assertEquals("0x0000", HexAddressParser.format(0))
        assertEquals("0x1000", HexAddressParser.format(0x1000))
        assertEquals("0x10000", HexAddressParser.format(0x10000))
        assertEquals("0xFFFFFF", HexAddressParser.format(0xFFFFFF))
    }

    @Test
    fun `format and parse round trip`() {
        listOf(0L, 0x1000L, 0x10000L, 0x3F0000L, 0xFFFFFFL).forEach { address ->
            assertEquals(address, HexAddressParser.parseOrThrow(HexAddressParser.format(address)))
        }
    }

    @Test
    fun `sector helpers align addresses`() {
        assertTrue(HexAddressParser.isSectorAligned(0x1000))
        assertTrue(HexAddressParser.isSectorAligned(0))
        assertFalse(HexAddressParser.isSectorAligned(0x1001))
        assertEquals(0x1000L, HexAddressParser.alignDown(0x1FFF))
        assertEquals(0x2000L, HexAddressParser.alignUp(0x1001))
        assertEquals(0x2000L, HexAddressParser.alignUp(0x2000))
    }

    @Test
    fun `presets cover merged and application images`() {
        assertEquals(0x0L, AddressPreset.MERGED.address)
        assertEquals(0x10000L, AddressPreset.APP.address)
    }
}
