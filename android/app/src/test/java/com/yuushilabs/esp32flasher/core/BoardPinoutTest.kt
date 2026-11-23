package com.yuushilabs.esp32flasher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardPinoutTest {
    private fun assignment(gpio: Int?, label: String) = PinAssignment(gpio = gpio, label = label)

    @Test
    fun `header layouts have the expected pin counts`() {
        assertEquals(19, BoardVariant.DEVKIT_38.left.size)
        assertEquals(19, BoardVariant.DEVKIT_38.right.size)
        assertEquals(38, BoardVariant.DEVKIT_38.pins.size)
        assertEquals(15, BoardVariant.DEVKIT_30.left.size)
        assertEquals(15, BoardVariant.DEVKIT_30.right.size)
        assertEquals(30, BoardVariant.DEVKIT_30.pins.size)
    }

    @Test
    fun `gpio numbers are unique inside each variant`() {
        BoardVariant.entries.forEach { variant ->
            val gpios = variant.pins.mapNotNull { it.gpio }
            assertEquals(gpios.size, gpios.toSet().size)
        }
    }

    @Test
    fun `the 38 pin board exposes the flash pins and the 30 pin board does not`() {
        (6..11).forEach { gpio ->
            assertNotNull("GPIO$gpio should exist on the 38 pin board", BoardVariant.DEVKIT_38.pinForGpio(gpio))
            assertNull("GPIO$gpio should not exist on the 30 pin board", BoardVariant.DEVKIT_30.pinForGpio(gpio))
        }
    }

    @Test
    fun `looks up pins by gpio number`() {
        val pin = BoardVariant.DEVKIT_38.pinForGpio(4)
        assertEquals("IO4", pin?.label)
        assertTrue(BoardVariant.DEVKIT_38.right.contains(pin))
        assertEquals("4", pin?.shortLabel)
        assertEquals("GPIO4", pin?.displayLabel)
    }

    @Test
    fun `resolves gpio highlights`() {
        val highlights = BoardPinout.resolveHighlights(
            BoardVariant.DEVKIT_38,
            listOf(assignment(4, "LED"), assignment(21, "SDA")),
        )
        assertEquals(2, highlights.size)
        assertTrue(highlights.keys.any { it.gpio == 4 })
        assertTrue(highlights.keys.any { it.gpio == 21 })
        assertEquals("LED", highlights.entries.first { it.key.gpio == 4 }.value.single().label)
    }

    @Test
    fun `resolves ground highlights for every ground pin`() {
        val highlights = BoardPinout.resolveHighlights(
            BoardVariant.DEVKIT_38,
            listOf(assignment(null, "GND")),
        )
        val groundPins = BoardVariant.DEVKIT_38.pins.filter { it.kind == BoardPinKind.GROUND }
        assertEquals(groundPins.size, highlights.size)
        assertTrue(highlights.keys.all { it.kind == BoardPinKind.GROUND })
    }

    @Test
    fun `resolves power and enable highlights`() {
        val power = BoardPinout.resolveHighlights(
            BoardVariant.DEVKIT_30,
            listOf(assignment(null, "3V3"), assignment(null, "VIN"), assignment(null, "EN")),
        )
        assertTrue(power.keys.any { it.label == "3V3" })
        assertTrue(power.keys.any { it.label == "VIN" })
        assertTrue(power.keys.any { it.kind == BoardPinKind.ENABLE })
    }

    @Test
    fun `unknown labels are ignored`() {
        val highlights = BoardPinout.resolveHighlights(
            BoardVariant.DEVKIT_38,
            listOf(assignment(null, "SPEAKER")),
        )
        assertTrue(highlights.isEmpty())
    }

    @Test
    fun `callout text joins the assigned roles`() {
        assertEquals(
            "LED / STATUS",
            BoardPinout.calloutText(listOf(assignment(4, "LED"), assignment(4, "STATUS"))),
        )
    }

    @Test
    fun `flash only and input only ranges are flagged`() {
        assertTrue(BoardPinout.isFlashOnly(6))
        assertTrue(BoardPinout.isFlashOnly(11))
        assertFalse(BoardPinout.isFlashOnly(12))
        assertTrue(BoardPinout.isInputOnly(34))
        assertTrue(BoardPinout.isInputOnly(39))
        assertFalse(BoardPinout.isInputOnly(33))
    }

    @Test
    fun `pin warnings explain the esp32 devkitc restrictions`() {
        val flashPin = BoardVariant.DEVKIT_38.pinForGpio(6)!!
        assertTrue(BoardPinout.warningsFor(flashPin).any { it.contains("フラッシュ") })

        val inputOnly = BoardVariant.DEVKIT_38.pinForGpio(34)!!
        assertTrue(BoardPinout.warningsFor(inputOnly).any { it.contains("入力専用") })

        val powerPin = BoardVariant.DEVKIT_38.pins.first { it.label == "3V3" }
        assertTrue(BoardPinout.warningsFor(powerPin).any { it.contains("3.3V") })

        val normal = BoardVariant.DEVKIT_38.pinForGpio(25)!!
        assertTrue(BoardPinout.warningsFor(normal).isEmpty())
    }
}
