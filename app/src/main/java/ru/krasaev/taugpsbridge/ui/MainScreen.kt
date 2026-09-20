package ru.krasaev.taugpsbridge.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.krasaev.taugpsbridge.model.AntennaState
import ru.krasaev.taugpsbridge.model.AntennaStatus
import ru.krasaev.taugpsbridge.model.BackupPpsStatus
import ru.krasaev.taugpsbridge.model.BackupState
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.FixType
import ru.krasaev.taugpsbridge.model.GpsData
import ru.krasaev.taugpsbridge.model.InsDrState
import ru.krasaev.taugpsbridge.model.InsInstallState
import ru.krasaev.taugpsbridge.model.InsStatus
import ru.krasaev.taugpsbridge.model.ModuleInfo
import ru.krasaev.taugpsbridge.model.SatelliteSystemInfo
import ru.krasaev.taugpsbridge.ui.components.StatusGray
import ru.krasaev.taugpsbridge.ui.components.StatusGreen
import ru.krasaev.taugpsbridge.ui.components.StatusRed
import ru.krasaev.taugpsbridge.ui.components.StatusYellow
import ru.krasaev.taugpsbridge.ui.theme.TAUGPSBridgeTheme
import ru.krasaev.taugpsbridge.viewmodel.GpsUiState
import java.util.Locale

// ═══════════════════════════════════════════════════════════════
//  Локально-независимые форматтеры
// ═══════════════════════════════════════════════════════════════

private fun f1(v: Double?): String =
    v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

private fun f1(v: Float?): String =
    v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

private fun f6(v: Double?): String =
    v?.let { String.format(Locale.US, "%.6f", it) } ?: "—"

// ═══════════════════════════════════════════════════════════════
//  Агрегация спутников по системе
//  Парсер хранит ключи "GPS_L1", "GPS_L5", "BDS_B1", "BDS_B2a", ...
// ═══════════════════════════════════════════════════════════════

private data class SystemAggregate(val count: Int, val cno: Double?)

/**
 * Агрегат по системе только из PRIMARY band,
 * чтобы не задваивать L1 и L5 (это одни и те же физические спутники).
 */
private fun GpsData.systemAggregate(system: String): SystemAggregate {
    val primaryKey = when (system) {
        "GPS", "GLO", "QZSS" -> "${system}_L1"
        "BDS" -> "BDS_B1"
        "GAL" -> "GAL_E1"
        else -> return SystemAggregate(0, null)
    }
    val info = satellitesBySystem[primaryKey] ?: return SystemAggregate(0, null)
    return SystemAggregate(info.satCount, info.avgCno)
}

// ═══════════════════════════════════════════════════════════════
//  Main screen
// ═══════════════════════════════════════════════════════════════

@Composable
fun MainScreen(
    uiState: GpsUiState,
    modifier: Modifier = Modifier
) {
    val gpsData = uiState.gpsData

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }
        item { SatellitesAndSignalBlock(gpsData = gpsData) }
        item { CoordinatesAndMotionBlock(gpsData = gpsData) }
        item { InsStatusBlock(gpsData = gpsData) }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// =========================================================================
// 1. СПУТНИКИ И СИГНАЛ (ВКЛЮЧАЯ GNSS-ФИКС, DOP И АНТЕННУ)
// =========================================================================
@Composable
private fun SatellitesAndSignalBlock(gpsData: GpsData) {
    val overallCno = gpsData.overallAvgCno
    val ant = gpsData.antennaStatus
    val backup = gpsData.backupPpsStatus

    val fixColor = when (gpsData.fixType) {
        FixType.RTK_FIXED, FixType.RTK_FLOAT, FixType.FIX_3D -> StatusGreen
        FixType.DGPS, FixType.FIX_2D -> StatusYellow
        FixType.NO_FIX -> StatusRed
    }

    val fixTitle = when (gpsData.ggaQuality) {
        4 -> "RTK Fixed"
        5 -> "RTK Float"
        1 -> "3D Fix"
        2 -> "DGPS"
        0 -> "Нет фикса"
        else -> if (gpsData.is3DFix) "3D Fix" else gpsData.fixType.displayName
    }

    val cnoColor = when {
        overallCno == null -> StatusGray
        overallCno >= 30.0 -> StatusGreen
        overallCno >= 25.0 -> StatusYellow
        else -> StatusRed
    }

    val cnoQualityText = when {
        overallCno == null -> "—"
        overallCno > 40.0 -> "🟢"
        overallCno >= 30.0 -> "🟢"
        overallCno >= 25.0 -> "🟡"
        overallCno >= 15.0 -> "🔴 Слабо"
        else -> "🔴 Нет приёма"
    }

    val antColor = when (ant.state) {
        AntennaState.OK -> StatusGreen
        AntennaState.OPEN -> StatusYellow
        AntennaState.SHORT -> StatusRed
        AntennaState.UNKNOWN -> StatusGray
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SatelliteAlt,
                    contentDescription = "Satellites",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "СПУТНИКИ И СИГНАЛ",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // GNSS Fix Badge + satellite counts
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = fixColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, fixColor)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusLed(color = fixColor, size = 10.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = fixTitle,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = fixColor
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "В поле зрения: ${gpsData.satellitesInView}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Используется: ${gpsData.satellitesUsed}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (gpsData.satellitesUsed > 0) StatusGreen else StatusGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // ─── АГРЕГАЦИЯ ПО СИСТЕМАМ ───
            val gps = gpsData.systemAggregate("GPS")
            val bds = gpsData.systemAggregate("BDS")
            val glo = gpsData.systemAggregate("GLO")
            val gal = gpsData.systemAggregate("GAL")

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ConstellationStat(name = "GPS", count = gps.count, avgCno = gps.cno)
                ConstellationStat(name = "BDS (Beidou)", count = bds.count, avgCno = bds.cno)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ConstellationStat(name = "GLONASS", count = glo.count, avgCno = glo.cno)
                ConstellationStat(name = "Galileo", count = gal.count, avgCno = gal.cno)
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // ─── СРЕДНИЙ C/N0 ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Средний сигнал (C/N0):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = overallCno?.let { "${f1(it)} дБГц" } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = cnoColor
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "($cnoQualityText)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ─── DOP ───
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Точность (DOP):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "PDOP: ${f1(gpsData.pdop)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "HDOP: ${f1(gpsData.hdop)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "VDOP: ${f1(gpsData.vdop)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // ─── АНТЕННА И ВРЕМЯ ФИКСА ───
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        "Состояние антенны",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusLed(color = antColor, size = 10.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = ant.state.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = antColor
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Диапазон",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (ant.isDualBand) "L1 + L5 ✅" else "Только L1 ⚠️",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (ant.isDualBand) StatusGreen else StatusYellow
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        "L1 / L5 C/N0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "L1: ${f1(ant.l1AvgCno)} дБГц  •  L5: ${f1(ant.l5AvgCno)} дБГц",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Время 1-го фикса",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = backup.timeToFirstFixSeconds?.let { "$it с" } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// =========================================================================
// 2. КООРДИНАТЫ И ДВИЖЕНИЕ
// =========================================================================
@Composable
private fun CoordinatesAndMotionBlock(gpsData: GpsData) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Coordinates",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "КООРДИНАТЫ И ДВИЖЕНИЕ",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        "Широта (Latitude)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = gpsData.latitude?.let { "${f6(it)}°" } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        "Долгота (Longitude)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = gpsData.longitude?.let { "${f6(it)}°" } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TelemetryParam(
                    title = "Высота (MSL)",
                    value = gpsData.altitudeMeters?.let { "${f1(it)} м" } ?: "—"
                )
                TelemetryParam(
                    title = "Скорость",
                    value = gpsData.speedKmh?.let { "${f1(it)} км/ч" } ?: "—"
                )
                TelemetryParam(
                    title = "Курс",
                    value = gpsData.bearingDegrees?.let { "${f1(it)}°" } ?: "—"
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val timeDate = listOfNotNull(
                gpsData.utcTimeString.ifEmpty { null },
                gpsData.utcDateString.ifEmpty { null }
            ).joinToString(" • ")

            TelemetryParam(
                title = "UTC Время и Дата",
                value = if (timeDate.isNotEmpty()) timeDate else "—"
            )
        }
    }
}

// =========================================================================
// 3. СТАТУС INS
// =========================================================================
@Composable
private fun InsStatusBlock(gpsData: GpsData) {
    val ins = gpsData.insStatus

    val drColor = when (ins.drState) {
        InsDrState.ACTIVE -> StatusGreen
        InsDrState.CALIBRATING, InsDrState.GNSS_ONLY -> StatusYellow
        InsDrState.OFF -> StatusRed
        InsDrState.UNKNOWN -> StatusGray
    }

    val installColor = when (ins.installState) {
        InsInstallState.FULL_READY -> StatusGreen
        InsInstallState.ROLL_READY, InsInstallState.YAW_READY, InsInstallState.DETECTING -> StatusYellow
        InsInstallState.ERROR -> StatusRed
        InsInstallState.UNKNOWN -> StatusGray
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(2.dp, drColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CompassCalibration,
                    contentDescription = "INS",
                    tint = drColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "🧭 СТАТУС INS",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = drColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusLed(color = drColor, size = 10.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = ins.drState.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = drColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Режим DR (dr):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusLed(color = drColor, size = 12.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (ins.rawDr.isNotEmpty())
                                "${ins.drState.title} [${ins.rawDr}]"
                            else
                                ins.drState.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = drColor
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        "Калибровка (install):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusLed(color = installColor, size = 12.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (ins.rawInstall.isNotEmpty())
                                "${ins.installState.title} [${ins.rawInstall}]"
                            else
                                ins.installState.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = installColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Recommendation",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Рекомендация водителю:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = ins.recommendation.ifBlank { "Ожидание данных..." },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// HELPER UI COMPONENTS
// =========================================================================
@Composable
private fun StatusLed(color: Color, size: Dp = 10.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, color.copy(alpha = 0.5f), CircleShape)
    )
}

@Composable
private fun TelemetryParam(title: String, value: String, alignEnd: Boolean = false) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ConstellationStat(name: String, count: Int, avgCno: Double?) {
    Column {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (count > 0)
                "$count сп. ${avgCno?.let { "(${f1(it)} дБГц)" } ?: ""}"
            else
                "0 сп.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (count > 0) MaterialTheme.colorScheme.onSurface else StatusGray
        )
    }
}

// =========================================================================
// PREVIEWS
// =========================================================================
@Preview(name = "Light Mode - Connected", showBackground = true)
@Preview(name = "Dark Mode - Connected", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun MainScreenPreview() {
    TAUGPSBridgeTheme {
        MainScreen(
            uiState = GpsUiState(
                connectionStatus = ConnectionStatus.Connected(
                    deviceName = "ttyUSB0",
                    baudRate = 115200
                ),
                selectedDeviceName = "ttyUSB0",
                selectedBaudRate = 115200,
                gpsData = GpsData(
                    latitude = 55.751244,
                    longitude = 37.618423,
                    altitudeMeters = 156.4,
                    speedKmh = 64.2,
                    bearingDegrees = 128.5f,
                    satellitesUsed = 24,
                    satellitesInView = 32,
                    hdop = 0.82f,
                    pdop = 1.34f,
                    vdop = 1.05f,
                    fixType = FixType.RTK_FIXED,
                    ggaQuality = 4,
                    is3DFix = true,
                    utcTimeString = "14:28:45 UTC",
                    utcDateString = "20.09.2026",
                    overallAvgCno = 38.5,
                    moduleInfo = ModuleInfo(
                        swVersion = "3.M8C.4e08c7",
                        hwVersion = "HD8040DF.017747a",
                        isDualFrequency = true,
                        isDetected = true
                    ),
                    insStatus = InsStatus(
                        rawDr = "A",
                        rawInstall = "3",
                        drState = InsDrState.ACTIVE,
                        installState = InsInstallState.FULL_READY,
                        recommendation = "INS полностью откалибрована и работает в штатном режиме."
                    ),
                    // Ключи должны совпадать с парсером: "GPS_L1", "BDS_B1", "GLO_L1", "GAL_E1"
                    satellitesBySystem = mapOf(
                        "GPS_L1" to SatelliteSystemInfo("GPS_L1", 10, 39.2),
                        "GPS_L5" to SatelliteSystemInfo("GPS_L5", 7, 36.5),
                        "BDS_B1" to SatelliteSystemInfo("BDS_B1", 8, 38.0),
                        "BDS_B2a" to SatelliteSystemInfo("BDS_B2a", 4, 35.1),
                        "GLO_L1" to SatelliteSystemInfo("GLO_L1", 4, 35.5),
                        "GAL_E1" to SatelliteSystemInfo("GAL_E1", 2, 36.1)
                    ),
                    antennaStatus = AntennaStatus(
                        state = AntennaState.OK,
                        isDualBand = true,
                        l1AvgCno = 39.5,
                        l5AvgCno = 37.2
                    ),
                    backupPpsStatus = BackupPpsStatus(
                        backupState = BackupState.HOT_START,
                        timeToFirstFixSeconds = 2L,
                        isPpsActive = true,
                        lastPpsTimestampMillis = System.currentTimeMillis()
                    )
                )
            )
        )
    }
}

@Preview(name = "Disconnected State", showBackground = true)
@Composable
fun MainScreenDisconnectedPreview() {
    TAUGPSBridgeTheme {
        MainScreen(
            uiState = GpsUiState(
                connectionStatus = ConnectionStatus.Disconnected,
                selectedDeviceName = null,
                gpsData = GpsData()
            )
        )
    }
}