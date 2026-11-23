package com.yuushilabs.esp32flasher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuushilabs.esp32flasher.core.PinActiveLevel
import com.yuushilabs.esp32flasher.core.PinAssignment
import com.yuushilabs.esp32flasher.core.PinDirection
import com.yuushilabs.esp32flasher.ui.LogEntry
import com.yuushilabs.esp32flasher.ui.LogLevel
import com.yuushilabs.esp32flasher.usb.FlashStage

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun StatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun LogList(
    logs: List<LogEntry>,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = true,
) {
    val listState = rememberLazyListState()
    // Key on the content, not the size: the log lists drop old lines once they reach their cap.
    LaunchedEffect(logs.size, logs.lastOrNull(), autoScroll) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }
    if (logs.isEmpty()) {
        Text(
            "ログはまだありません",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(logs) { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.time,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = logColor(entry.level),
                )
            }
        }
    }
}

@Composable
fun logColor(level: LogLevel): Color = when (level) {
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    LogLevel.SUCCESS -> Color(0xFF15803D)
    LogLevel.WARN -> Color(0xFFB45309)
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
}

fun stageLabel(stage: FlashStage?): String = when (stage) {
    FlashStage.CONNECTING -> "ブートローダーへ接続中"
    FlashStage.SYNCING -> "同期中 (SYNC)"
    FlashStage.CHIP_INFO -> "チップ情報を取得中"
    FlashStage.ATTACHING -> "フラッシュを初期化中"
    FlashStage.ERASING -> "フラッシュを消去中"
    FlashStage.WRITING -> "書き込み中"
    FlashStage.FINISHING -> "書き込みを完了中"
    FlashStage.RESETTING -> "リセット中"
    FlashStage.DONE -> "書き込み完了"
    null -> "待機中"
}

fun pinDirectionLabel(direction: PinDirection): String = when (direction) {
    PinDirection.INPUT -> "入力"
    PinDirection.OUTPUT -> "出力"
    PinDirection.BIDIRECTIONAL -> "双方向"
    PinDirection.UNKNOWN -> "—"
}

fun pinActiveLevelLabel(level: PinActiveLevel): String = when (level) {
    PinActiveLevel.HIGH -> "Active HIGH"
    PinActiveLevel.LOW -> "Active LOW"
    PinActiveLevel.UNKNOWN -> "—"
}

/**
 * Wiring guide for the selected firmware: the manifest pin list, the interactive board diagram
 * and the ESP32-DevKitC reference notes.
 */
@Composable
fun PinWiringGuideCard(
    pins: List<PinAssignment>,
    notes: List<String>,
    powerNote: String? = null,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Pin Wiring Guide",
        subtitle = "flash-manifest.json のピン情報と ESP32-DevKitC の注意点",
        modifier = modifier,
    ) {
        if (pins.isEmpty()) {
            Text(
                "このファームウェアにはピン情報が含まれていません。ボード図でピンをタップすると標準の注意点を確認できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            pins.forEach { pin -> PinRow(pin) }
        }
        powerNote?.takeIf { it.isNotBlank() }?.let { note ->
            HorizontalDivider()
            Text("電源注意", style = MaterialTheme.typography.labelLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (notes.isNotEmpty()) {
            HorizontalDivider()
            Text("配線メモ", style = MaterialTheme.typography.labelLarge)
            notes.forEach { note ->
                Text("・$note", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        DevKitCBoardSection(assignments = pins)
        HorizontalDivider()
        DevKitCReference()
    }
}

@Composable
fun PinWiringDialog(
    pins: List<PinAssignment>,
    notes: List<String>,
    powerNote: String? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
        title = { Text("Pin Wiring Guide") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pins.isEmpty()) {
                    Text("ピン情報はありません。", style = MaterialTheme.typography.bodySmall)
                } else {
                    pins.forEach { pin -> PinRow(pin) }
                }
                powerNote?.takeIf { it.isNotBlank() }?.let { note ->
                    HorizontalDivider()
                    Text("電源注意", style = MaterialTheme.typography.labelLarge)
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (notes.isNotEmpty()) {
                    HorizontalDivider()
                    notes.forEach { note ->
                        Text("・$note", style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                DevKitCBoardSection(assignments = pins)
                HorizontalDivider()
                DevKitCReference()
            }
        },
    )
}

@Composable
private fun PinRow(pin: PinAssignment) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusBadge(
            text = pin.gpioLabel,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(pin.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                listOf(pinDirectionLabel(pin.direction), pinActiveLevelLabel(pin.activeLevel))
                    .filter { it != "—" }
                    .joinToString(" / ")
                    .ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pin.note?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun DevKitCReference() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "ESP32-DevKitC リファレンス",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        ReferenceLine("GPIO6〜GPIO11", "内蔵 SPI フラッシュ専用。外部配線に使用しないでください。")
        ReferenceLine("GPIO34〜GPIO39", "入力専用ピン。出力や内部プルアップは使えません。")
        ReferenceLine("ロジックレベル", "3.3V。5V を直接入力すると破損します。")
        ReferenceLine("ブート設定", "IO0 = LOW でブートローダー起動。書き込み後は自動リセットされます。")
    }
}

@Composable
private fun ReferenceLine(title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(110.dp),
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            modifier = Modifier.weight(1f),
        )
    }
}
