package ru.krasaev.taugpsbridge.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.krasaev.taugpsbridge.model.AntennaState
import ru.krasaev.taugpsbridge.model.BackupState
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.FixType
import ru.krasaev.taugpsbridge.model.GpsData
import ru.krasaev.taugpsbridge.model.InsDrState
import ru.krasaev.taugpsbridge.model.InsInstallState
import ru.krasaev.taugpsbridge.viewmodel.GpsUiState

private val StatusGreen = Color(0xFF4CAF50)
private val StatusYellow = Color(0xFFFFB300)
private val StatusRed = Color(0xFFF44336)
private val StatusGray = Color(0xFF9E9E9E)

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
        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        // БЛОК 1: СОСТОЯНИЕ ПОДКЛЮЧЕНИЯ И ВЕРСИЯ МОДУЛЯ
        item {
            Block1ConnectionAndModule(uiState = uiState)
        }

        // БЛОК 2: GNSS-ФИКС И КООРДИНАТЫ
        item {
            Block2GnssFixAndCoordinates(gpsData = gpsData)
        }

        // БЛОК 3: СТАТУС INS (ИНЕРЦИАЛЬНОЙ СИСТЕМЫ) — ГЛАВНЫЙ БЛОК
        item {
            Block3InsStatus(gpsData = gpsData)
        }

        // БЛОК 4: СПУТНИКИ И КАЧЕСТВО ПРИЁМА
        item {
            Block4SatellitesAndQuality(gpsData = gpsData)
        }

        // БЛОК 5: АНТЕННА
        item {
            Block5Antenna(gpsData = gpsData)
        }

        // БЛОК 6: BACKUP И PPS
        item {
            Block6BackupAndPps(gpsData = gpsData)
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// =========================================================================
// БЛОК 1: СОСТОЯНИЕ ПОДКЛЮЧЕНИЯ И ВЕРСИЯ МОДУЛЯ
// =========================================================================
@Composable
private fun Block1ConnectionAndModule(uiState: GpsUiState) {
    val status = uiState.connectionStatus
    val moduleInfo = uiState.gpsData.moduleInfo

    val isConnected = status is ConnectionStatus.Connected
    val isConnecting = status is ConnectionStatus.Connecting

    val statusColor = when {
        isConnected -> StatusGreen
        isConnecting -> StatusYellow
        else -> StatusRed
    }

    val statusText = when (status) {
        is ConnectionStatus.Connected -> "USB подключён"
        is ConnectionStatus.Connecting -> "Подключение к USB..."
        is ConnectionStatus.Disconnected -> "Нет устройства"
        is ConnectionStatus.Error -> "Ошибка: ${status.message}"
    }

    val portName = when (status) {
        is ConnectionStatus.Connected -> status.deviceName
        else -> uiState.selectedDeviceName ?: "Не выбрано"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                StatusLed(color = statusColor)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "БЛОК 1: ПОДКЛЮЧЕНИЕ И МОДУЛЬ",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Port & Baud Rate
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Порт устройства", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(portName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Скорость", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${uiState.selectedBaudRate} бод", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Module Model & Firmware
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Модель модуля", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = moduleInfo.displayModel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Прошивка", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = moduleInfo.displayFirmware,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Frequency Type
            Row(verticalAlignment = Alignment.CenterVertically) {
                val typeColor = when (moduleInfo.isDualFrequency) {
                    true -> StatusGreen
                    false -> StatusYellow
                    null -> StatusGray
                }
                val typeEmoji = when (moduleInfo.isDualFrequency) {
                    true -> "🟢"
                    false -> "⚠️"
                    null -> "⚪"
                }
                Text("Тип: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "$typeEmoji ${moduleInfo.typeDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = typeColor
                )
            }
        }
    }
}

// =========================================================================
// БЛОК 2: GNSS-ФИКС И КООРДИНАТЫ
// =========================================================================
@Composable
private fun Block2GnssFixAndCoordinates(gpsData: GpsData) {
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

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header with Large Fix Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "GNSS",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "БЛОК 2: GNSS-ФИКС И КООРДИНАТЫ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Large Fix Badge
                Surface(
                    color = fixColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, fixColor)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusLed(color = fixColor, size = 10.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = fixTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = fixColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Lat & Lon in large clear numbers
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Широта (Latitude)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = gpsData.latitude?.let { "%.6f°".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Долгота (Longitude)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = gpsData.longitude?.let { "%.6f°".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Altitude, Speed, Course
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TelemetryParam(title = "Высота", value = gpsData.altitudeMeters?.let { "%.1f м".format(it) } ?: "—")
                TelemetryParam(title = "Скорость", value = gpsData.speedKmh?.let { "%.1f км/ч".format(it) } ?: "0.0 км/ч")
                TelemetryParam(title = "Курс", value = gpsData.bearingDegrees?.let { "%.1f°".format(it) } ?: "—")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sats in solution, HDOP, UTC time/date
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TelemetryParam(title = "Спутников в решении", value = "${gpsData.satellitesUsed}")
                TelemetryParam(title = "HDOP", value = gpsData.hdop?.let { "%.2f".format(it) } ?: "—")
                val timeDate = listOfNotNull(
                    gpsData.utcTimeString.ifEmpty { null },
                    gpsData.utcDateString.ifEmpty { null }
                ).joinToString(" • ")
                TelemetryParam(
                    title = "UTC Время и Дата",
                    value = if (timeDate.isNotEmpty()) timeDate else "—",
                    alignEnd = true
                )
            }
        }
    }
}

// =========================================================================
// БЛОК 3: СТАТУС INS (ИНЕРЦИАЛЬНОЙ СИСТЕМЫ) — ГЛАВНЫЙ БЛОК
// =========================================================================
@Composable
private fun Block3InsStatus(gpsData: GpsData) {
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

    // Emphasize with primaryContainer or prominent border
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, drColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Main Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CompassCalibration,
                        contentDescription = "INS",
                        tint = drColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "🧭 БЛОК 3: СТАТУС INS (ГЛАВНЫЙ)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = drColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = ins.drState.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = drColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // DR State & Installation State in 2 columns
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Режим DR (dr):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusLed(color = drColor, size = 12.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (ins.rawDr.isNotEmpty()) "${ins.drState.title} [${ins.rawDr}]" else ins.drState.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = drColor
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Калибровка установки (install):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusLed(color = installColor, size = 12.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (ins.rawInstall.isNotEmpty()) "${ins.installState.title} [${ins.rawInstall}]" else ins.installState.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = installColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // User Recommendation Box
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                            text = ins.recommendation,
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
// БЛОК 4: СПУТНИКИ И КАЧЕСТВО ПРИЁМА
// =========================================================================
@Composable
private fun Block4SatellitesAndQuality(gpsData: GpsData) {
    val overallCno = gpsData.overallAvgCno

    val cnoColor = when {
        overallCno == null -> StatusGray
        overallCno > 40.0 -> StatusGreen
        overallCno >= 30.0 -> StatusGreen
        overallCno >= 25.0 -> StatusYellow
        overallCno >= 15.0 -> StatusRed
        else -> StatusRed
    }

    val cnoQualityText = when {
        overallCno == null -> "—"
        overallCno > 40.0 -> "🟢 Отлично"
        overallCno >= 30.0 -> "🟢 Хорошо"
        overallCno >= 25.0 -> "🟡 Удовлетворительно"
        overallCno >= 15.0 -> "🔴 Слабо"
        else -> "🔴 Нет приёма"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SatelliteAlt,
                        contentDescription = "Satellites",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "БЛОК 4: СПУТНИКИ И СИГНАЛ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = "Всего в поле: ${gpsData.satellitesInView}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Constellations grid
            val gps = gpsData.satellitesBySystem["GPS"]
            val bds = gpsData.satellitesBySystem["BDS"]
            val glo = gpsData.satellitesBySystem["GLONASS"]
            val gal = gpsData.satellitesBySystem["Galileo"]

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ConstellationStat(name = "GPS", count = gps?.satCount ?: 0, avgCno = gps?.avgCno)
                ConstellationStat(name = "BDS (Beidou)", count = bds?.satCount ?: 0, avgCno = bds?.avgCno)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ConstellationStat(name = "GLONASS", count = glo?.satCount ?: 0, avgCno = glo?.avgCno)
                ConstellationStat(name = "Galileo", count = gal?.satCount ?: 0, avgCno = gal?.avgCno)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Average C/N0 bar & DOPs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Средний C/N0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = overallCno?.let { "%.1f дБГц".format(it) } ?: "—",
                            style = MaterialTheme.typography.bodyLarge,
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

                Column(horizontalAlignment = Alignment.End) {
                    Text("DOP (Точность)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "P: ${gpsData.pdop?.let { "%.1f".format(it) } ?: "—"}  H: ${gpsData.hdop?.let { "%.1f".format(it) } ?: "—"}  V: ${gpsData.vdop?.let { "%.1f".format(it) } ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// =========================================================================
// БЛОК 5: АНТЕННА
// =========================================================================
@Composable
private fun Block5Antenna(gpsData: GpsData) {
    val ant = gpsData.antennaStatus

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Radio,
                    contentDescription = "Antenna",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "БЛОК 5: АНТЕННА",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Antenna State & Band
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Тип антенны", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("Диапазон", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (ant.isDualBand) "L1 + L5 ✅" else "Только L1 ⚠️",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (ant.isDualBand) StatusGreen else StatusYellow
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // L1 & L5 C/N0 values
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("L1 C/N0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = ant.l1AvgCno?.let { "%.1f дБГц".format(it) } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("L5 C/N0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = ant.l5AvgCno?.let { "%.1f дБГц".format(it) } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// =========================================================================
// БЛОК 6: BACKUP И PPS
// =========================================================================
@Composable
private fun Block6BackupAndPps(gpsData: GpsData) {
    val backup = gpsData.backupPpsStatus

    val backupColor = when (backup.backupState) {
        BackupState.HOT_START -> StatusGreen
        BackupState.WARM_START -> StatusYellow
        BackupState.COLD_START -> StatusRed
        BackupState.WAITING, BackupState.UNKNOWN -> StatusGray
    }

    // PPS Blinking animation
    val infiniteTransition = rememberInfiniteTransition(label = "pps")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ppsAlpha"
    )

    val isPpsActive = backup.isPpsActive && (System.currentTimeMillis() - backup.lastPpsTimestampMillis < 2500)
    val ppsAlpha = if (isPpsActive) alphaAnim else 0.3f
    val ppsColor = if (isPpsActive) StatusGreen else StatusRed
    val ppsText = if (isPpsActive) "PPS активен (1 Гц)" else "PPS нет"

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = "Backup",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "БЛОК 6: BACKUP И PPS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Backup status & PPS Indicator
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Батарейка Backup", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusLed(color = backupColor, size = 10.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = backup.backupState.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = backupColor
                        )
                    }
                    if (backup.timeToFirstFixSeconds != null) {
                        Text(
                            text = "Время 1-го фикса: ${backup.timeToFirstFixSeconds} с",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("Сигнал PPS (1 сек)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .alpha(ppsAlpha)
                                .clip(CircleShape)
                                .background(ppsColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = ppsText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ppsColor
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
private fun StatusLed(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp) {
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
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "$count сп. ${avgCno?.let { "(%.1f dB)".format(it) } ?: ""}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
