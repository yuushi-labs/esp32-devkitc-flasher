package com.yuushilabs.esp32flasher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuushilabs.esp32flasher.core.BoardPin
import com.yuushilabs.esp32flasher.core.BoardPinKind
import com.yuushilabs.esp32flasher.core.BoardPinout
import com.yuushilabs.esp32flasher.core.BoardVariant
import com.yuushilabs.esp32flasher.core.PinAssignment
import kotlin.math.hypot

private val PCB_COLOR = Color(0xFF0F172A)
private val PCB_EDGE = Color(0xFF334155)
private val PIN_GOLD = Color(0xFFD4A017)
private val SHIELD_COLOR = Color(0xFF94A3B8)
private val GPIO_HIGHLIGHT = Color(0xFF22D3EE)
private val GROUND_HIGHLIGHT = Color(0xFFF59E0B)
private val POWER_HIGHLIGHT = Color(0xFFF43F5E)
private val ENABLE_HIGHLIGHT = Color(0xFFA78BFA)
private val UART_HIGHLIGHT = Color(0xFF34D399)
private val LABEL_COLOR = Color(0xFFCBD5E1)
private val BOARD_TITLE_COLOR = Color(0xFF0F172A)

private enum class PinSide { LEFT, RIGHT }

private data class PinGeometry(
    val pin: BoardPin,
    val center: Offset,
    val side: PinSide,
    val row: Int,
)

private data class BoardGeometry(
    val boardLeft: Float,
    val boardTop: Float,
    val boardWidth: Float,
    val boardHeight: Float,
    val leftPins: List<PinGeometry>,
    val rightPins: List<PinGeometry>,
    val pinRadius: Float,
) {
    val allPins: List<PinGeometry> get() = leftPins + rightPins

    fun hitTest(x: Float, y: Float): PinGeometry? {
        val threshold = pinRadius * 3.2f
        return allPins
            .map { geometry -> geometry to hypot(geometry.center.x - x, geometry.center.y - y) }
            .filter { (_, distance) -> distance <= threshold }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }
}

/**
 * Interactive top view of an ESP32-DevKitC. Pins referenced by the firmware manifest are ringed
 * in colour and labelled, and tapping a pin reports it through [onPinSelected].
 */
@Composable
fun DevKitCBoardSection(
    assignments: List<PinAssignment>,
    modifier: Modifier = Modifier,
) {
    var variant by remember { mutableStateOf(BoardVariant.DEVKIT_38) }
    var selectedPin by remember { mutableStateOf<BoardPin?>(null) }
    val highlights = remember(variant, assignments) {
        BoardPinout.resolveHighlights(variant, assignments)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "ESP32-DevKitC ボード図",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            BoardVariant.entries.forEach { candidate ->
                FilterChip(
                    selected = variant == candidate,
                    onClick = {
                        variant = candidate
                        selectedPin = null
                    },
                    label = { Text(candidate.displayName) },
                )
            }
        }

        DevKitCBoard(
            variant = variant,
            highlights = highlights,
            selectedPin = selectedPin,
            onPinSelected = { selectedPin = it },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f),
        )

        BoardLegend()

        val pin = selectedPin
        if (pin != null) {
            SelectedPinDetails(pin, highlights[pin].orEmpty())
        } else {
            Text(
                "ピンをタップすると役割と配線メモを表示します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DevKitCBoard(
    variant: BoardVariant,
    highlights: Map<BoardPin, List<PinAssignment>>,
    selectedPin: BoardPin?,
    onPinSelected: (BoardPin) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.pointerInput(variant) {
            detectTapGestures { offset ->
                val geometry = layoutBoard(size.width.toFloat(), size.height.toFloat(), variant)
                geometry.hitTest(offset.x, offset.y)?.let { onPinSelected(it.pin) }
            }
        },
    ) {
        drawBoard(
            geometry = layoutBoard(size.width, size.height, variant),
            variant = variant,
            highlights = highlights,
            selectedPin = selectedPin,
            measurer = textMeasurer,
        )
    }
}

@Composable
private fun BoardLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(GPIO_HIGHLIGHT, "GPIO")
        LegendDot(GROUND_HIGHLIGHT, "GND")
        LegendDot(POWER_HIGHLIGHT, "電源")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SelectedPinDetails(pin: BoardPin, assignments: List<PinAssignment>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusBadge(
                text = pin.displayLabel,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(pin.label, style = MaterialTheme.typography.bodySmall)
        }
        if (assignments.isEmpty()) {
            Text(
                "このピンは flash-manifest.json で参照されていません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            assignments.forEach { assignment ->
                Text(
                    assignment.label + " — " +
                        listOf(
                            pinDirectionLabel(assignment.direction),
                            pinActiveLevelLabel(assignment.activeLevel),
                        ).filter { it != "—" }.joinToString(" / "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                assignment.note?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        BoardPinout.warningsFor(pin).forEach { warning ->
            Text(
                "⚠ $warning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

private fun layoutBoard(width: Float, height: Float, variant: BoardVariant): BoardGeometry {
    val boardLeft = width * 0.11f
    val boardTop = height * 0.02f
    val boardWidth = width * 0.78f
    val boardHeight = height * 0.96f
    val headerInset = boardWidth * 0.10f
    val topPad = boardHeight * 0.20f
    val bottomPad = boardHeight * 0.12f
    val usable = (boardHeight - topPad - bottomPad).coerceAtLeast(1f)

    fun sidePins(pins: List<BoardPin>, side: PinSide): List<PinGeometry> {
        if (pins.isEmpty()) return emptyList()
        val spacing = if (pins.size > 1) usable / (pins.size - 1) else 0f
        val x = if (side == PinSide.LEFT) boardLeft + headerInset else boardLeft + boardWidth - headerInset
        return pins.mapIndexed { index, pin ->
            PinGeometry(
                pin = pin,
                center = Offset(x, boardTop + topPad + index * spacing),
                side = side,
                row = index,
            )
        }
    }

    val leftPins = sidePins(variant.left, PinSide.LEFT)
    val rightPins = sidePins(variant.right, PinSide.RIGHT)
    val spacing = if (variant.left.size > 1) usable / (variant.left.size - 1) else usable
    return BoardGeometry(
        boardLeft = boardLeft,
        boardTop = boardTop,
        boardWidth = boardWidth,
        boardHeight = boardHeight,
        leftPins = leftPins,
        rightPins = rightPins,
        pinRadius = (spacing * 0.22f).coerceIn(3f, 10f),
    )
}

private fun DrawScope.drawBoard(
    geometry: BoardGeometry,
    variant: BoardVariant,
    highlights: Map<BoardPin, List<PinAssignment>>,
    selectedPin: BoardPin?,
    measurer: TextMeasurer,
) {
    val boardOrigin = Offset(geometry.boardLeft, geometry.boardTop)
    val boardSize = Size(geometry.boardWidth, geometry.boardHeight)
    drawRoundRect(PCB_COLOR, boardOrigin, boardSize, CornerRadius(20f, 20f))
    drawRoundRect(PCB_EDGE, boardOrigin, boardSize, CornerRadius(20f, 20f), style = Stroke(2f))

    val shieldLeft = geometry.boardLeft + geometry.boardWidth * 0.14f
    val shieldTop = geometry.boardTop + geometry.boardHeight * 0.015f
    val shieldWidth = geometry.boardWidth * 0.72f
    val shieldHeight = geometry.boardHeight * 0.09f
    drawRoundRect(
        SHIELD_COLOR,
        Offset(shieldLeft, shieldTop),
        Size(shieldWidth, shieldHeight),
        CornerRadius(6f, 6f),
    )

    val moduleTop = shieldTop + shieldHeight + geometry.boardHeight * 0.012f
    val moduleHeight = geometry.boardHeight * 0.09f
    drawRoundRect(
        Color(0xFF1E293B),
        Offset(shieldLeft, moduleTop),
        Size(shieldWidth, moduleHeight),
        CornerRadius(6f, 6f),
    )
    drawCenteredLabel(
        measurer = measurer,
        text = "ESP-WROOM-32",
        centerX = geometry.boardLeft + geometry.boardWidth / 2f,
        centerY = shieldTop + shieldHeight / 2f,
        style = TextStyle(
            fontSize = 8.sp,
            color = BOARD_TITLE_COLOR,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        ),
    )
    drawCenteredLabel(
        measurer = measurer,
        text = "ESP32-DevKitC / ${variant.displayName}",
        centerX = geometry.boardLeft + geometry.boardWidth / 2f,
        centerY = moduleTop + moduleHeight / 2f,
        style = TextStyle(
            fontSize = 7.sp,
            color = LABEL_COLOR,
            fontFamily = FontFamily.Monospace,
        ),
    )

    val usbWidth = geometry.boardWidth * 0.22f
    val usbHeight = geometry.boardHeight * 0.032f
    val usbLeft = geometry.boardLeft + (geometry.boardWidth - usbWidth) / 2f
    val usbTop = geometry.boardTop + geometry.boardHeight - usbHeight * 1.5f
    drawRoundRect(
        Color(0xFF64748B),
        Offset(usbLeft, usbTop),
        Size(usbWidth, usbHeight),
        CornerRadius(4f, 4f),
    )
    drawCenteredLabel(
        measurer = measurer,
        text = "USB",
        centerX = usbLeft + usbWidth / 2f,
        centerY = usbTop + usbHeight / 2f,
        style = TextStyle(fontSize = 6.sp, color = Color(0xFF0F172A), fontFamily = FontFamily.Monospace),
    )

    val buttonWidth = geometry.boardWidth * 0.11f
    val buttonHeight = geometry.boardHeight * 0.028f
    val buttonTop = usbTop - buttonHeight * 1.8f
    drawRoundRect(
        Color(0xFF334155),
        Offset(geometry.boardLeft + geometry.boardWidth * 0.14f, buttonTop),
        Size(buttonWidth, buttonHeight),
        CornerRadius(3f, 3f),
    )
    drawRoundRect(
        Color(0xFF334155),
        Offset(geometry.boardLeft + geometry.boardWidth * 0.75f - buttonWidth, buttonTop),
        Size(buttonWidth, buttonHeight),
        CornerRadius(3f, 3f),
    )
    drawCenteredLabel(
        measurer = measurer,
        text = "EN",
        centerX = geometry.boardLeft + geometry.boardWidth * 0.14f + buttonWidth / 2f,
        centerY = buttonTop + buttonHeight / 2f,
        style = TextStyle(fontSize = 6.sp, color = LABEL_COLOR, fontFamily = FontFamily.Monospace),
    )
    drawCenteredLabel(
        measurer = measurer,
        text = "BOOT",
        centerX = geometry.boardLeft + geometry.boardWidth * 0.75f - buttonWidth / 2f,
        centerY = buttonTop + buttonHeight / 2f,
        style = TextStyle(fontSize = 5.sp, color = LABEL_COLOR, fontFamily = FontFamily.Monospace),
    )

    val pinLabelStyle = TextStyle(
        fontSize = 6.sp,
        color = LABEL_COLOR,
        fontFamily = FontFamily.Monospace,
    )
    geometry.allPins.forEach { pinGeometry ->
        drawCircle(PIN_GOLD, radius = geometry.pinRadius, center = pinGeometry.center)
        drawCircle(
            Color(0xFF7C5E10),
            radius = geometry.pinRadius,
            center = pinGeometry.center,
            style = Stroke(1f),
        )
        val labelX = if (pinGeometry.side == PinSide.LEFT) {
            pinGeometry.center.x + geometry.pinRadius * 2.2f
        } else {
            pinGeometry.center.x - geometry.pinRadius * 2.2f
        }
        drawLabel(
            measurer = measurer,
            text = pinGeometry.pin.shortLabel,
            style = pinLabelStyle,
            x = labelX,
            centerY = pinGeometry.center.y,
            rightAlign = pinGeometry.side == PinSide.RIGHT,
        )
    }

    geometry.allPins.forEach { pinGeometry ->
        val assignments = highlights[pinGeometry.pin] ?: return@forEach
        val color = highlightColor(pinGeometry.pin)
        drawCircle(color.copy(alpha = 0.22f), radius = geometry.pinRadius * 2.6f, center = pinGeometry.center)
        drawCircle(
            color,
            radius = geometry.pinRadius * 2.4f,
            center = pinGeometry.center,
            style = Stroke(width = 2.5f),
        )
        val calloutStyle = TextStyle(
            fontSize = 8.sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        val text = BoardPinout.calloutText(assignments)
        val layout = measurer.measure(text, calloutStyle)
        val isLeft = pinGeometry.side == PinSide.LEFT
        val calloutX = if (isLeft) {
            geometry.boardLeft + geometry.boardWidth * 0.30f
        } else {
            geometry.boardLeft + geometry.boardWidth * 0.70f - layout.size.width
        }
        val calloutY = pinGeometry.center.y - layout.size.height / 2f
        drawRoundRect(
            Color(0xFF0B1220).copy(alpha = 0.85f),
            Offset(calloutX - 4f, calloutY - 2f),
            Size(layout.size.width + 8f, layout.size.height + 4f),
            CornerRadius(3f, 3f),
        )
        val lineStart = if (isLeft) {
            pinGeometry.center.x + geometry.pinRadius * 2.6f
        } else {
            pinGeometry.center.x - geometry.pinRadius * 2.6f
        }
        val lineEnd = if (isLeft) calloutX - 5f else calloutX + layout.size.width + 5f
        drawLine(
            color = color.copy(alpha = 0.7f),
            start = Offset(lineStart, pinGeometry.center.y),
            end = Offset(lineEnd, pinGeometry.center.y),
            strokeWidth = 1.5f,
        )
        drawText(layout, topLeft = Offset(calloutX, calloutY))
    }

    if (selectedPin != null) {
        geometry.allPins.firstOrNull { it.pin == selectedPin }?.let { pinGeometry ->
            drawCircle(
                Color.White,
                radius = geometry.pinRadius * 3.1f,
                center = pinGeometry.center,
                style = Stroke(width = 2f),
            )
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    centerY: Float,
    rightAlign: Boolean,
) {
    val layout = measurer.measure(text, style)
    val left = if (rightAlign) x - layout.size.width else x
    drawText(layout, topLeft = Offset(left, centerY - layout.size.height / 2f))
}

private fun DrawScope.drawCenteredLabel(
    measurer: TextMeasurer,
    text: String,
    centerX: Float,
    centerY: Float,
    style: TextStyle,
) {
    val layout = measurer.measure(text, style)
    drawText(
        layout,
        topLeft = Offset(centerX - layout.size.width / 2f, centerY - layout.size.height / 2f),
    )
}

private fun highlightColor(pin: BoardPin): Color = when (pin.kind) {
    BoardPinKind.GPIO -> GPIO_HIGHLIGHT
    BoardPinKind.GROUND -> GROUND_HIGHLIGHT
    BoardPinKind.POWER -> POWER_HIGHLIGHT
    BoardPinKind.ENABLE -> ENABLE_HIGHLIGHT
    BoardPinKind.UART -> UART_HIGHLIGHT
}
