package ru.krasaev.taugpsbridge.gps

import ru.krasaev.taugpsbridge.model.AntennaState
import ru.krasaev.taugpsbridge.model.AntennaStatus
import ru.krasaev.taugpsbridge.model.BackupPpsStatus
import ru.krasaev.taugpsbridge.model.BackupState
import ru.krasaev.taugpsbridge.model.FixType
import ru.krasaev.taugpsbridge.model.GpsData
import ru.krasaev.taugpsbridge.model.InsDrState
import ru.krasaev.taugpsbridge.model.InsInstallState
import ru.krasaev.taugpsbridge.model.InsStatus
import ru.krasaev.taugpsbridge.model.ModuleInfo
import ru.krasaev.taugpsbridge.model.SatelliteSystemInfo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor

class NmeaParser {

    private var currentData = GpsData()
    private var lastGsaFixMode: Int = 1 // 1: No fix, 2: 2D, 3: 3D
    private var lastGgaQuality: Int = 0

    private var connectionStartTime: Long = 0L
    private var firstFixRecorded: Boolean = false

    // Cache for satellites C/N0: constellation -> list of snr values
    private val satCnoMap = mutableMapOf<String, MutableMap<Int, Double>>()
    private val l1CnoList = mutableListOf<Double>()
    private val l5CnoList = mutableListOf<Double>()
    private var isL5Detected: Boolean = false

    fun onConnected() {
        connectionStartTime = System.currentTimeMillis()
        firstFixRecorded = false
        currentData = currentData.copy(
            backupPpsStatus = BackupPpsStatus(
                backupState = BackupState.WAITING
            )
        )
    }

    fun parseSentence(sentence: String): GpsData {
        val trimmed = sentence.trim()
        if (!trimmed.startsWith("$") && !trimmed.startsWith("!")) {
            return currentData
        }

        // Strip checksum if present (*XX)
        val payload = if (trimmed.contains("*")) {
            trimmed.substringBefore("*")
        } else {
            trimmed
        }

        val tokens = payload.split(",")
        if (tokens.isEmpty()) return currentData

        val type = tokens[0].uppercase(Locale.ROOT)
        val now = System.currentTimeMillis()

        // Update PPS indicator on valid incoming sentence
        val updatedPps = currentData.backupPpsStatus.copy(
            isPpsActive = true,
            lastPpsTimestampMillis = now
        )
        currentData = currentData.copy(backupPpsStatus = updatedPps)

        try {
            when {
                type.endsWith("GGA") -> parseGGA(tokens, trimmed)
                type.endsWith("GSA") -> parseGSA(tokens, trimmed)
                type.endsWith("RMC") -> parseRMC(tokens, trimmed)
                type.endsWith("VTG") -> parseVTG(tokens, trimmed)
                type.endsWith("GSV") -> parseGSV(type, tokens, trimmed)
                type.endsWith("TXT") -> parseTXT(tokens, trimmed)
                else -> {
                    currentData = currentData.copy(rawSentence = trimmed)
                }
            }
        } catch (e: Exception) {
            // Ignore corrupted sentence
        }

        return currentData
    }

    fun parseBinaryVersionResponse(data: ByteArray): ModuleInfo? {
        // Binary response format (40 bytes):
        // F1 D9 0A 04 20 00 [16 bytes SW] [16 bytes HW] CK1 CK2
        if (data.size < 38) return null

        val swBytes = data.copyOfRange(6, minOf(22, data.size))
        val hwBytes = data.copyOfRange(22, minOf(38, data.size))

        val sw = String(swBytes, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        val hw = String(hwBytes, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }

        val isDual = when {
            hw.contains("1216A00", ignoreCase = true) -> true
            hw.contains("1216AFX", ignoreCase = true) -> false
            else -> null
        }

        val info = ModuleInfo(
            swVersion = sw,
            hwVersion = hw,
            isDualFrequency = isDual,
            isDetected = true
        )

        currentData = currentData.copy(moduleInfo = info)
        return info
    }

    private fun parseGGA(tokens: List<String>, raw: String) {
        // $GNGGA,hhmmss.ss,llll.ll,a,yyyyy.yy,a,x,xx,x.x,x.x,M,x.x,M,x.x,xxxx
        val timeStr = tokens.getOrNull(1)
        val latStr = tokens.getOrNull(2)
        val latDir = tokens.getOrNull(3)
        val lonStr = tokens.getOrNull(4)
        val lonDir = tokens.getOrNull(5)
        val qualityStr = tokens.getOrNull(6)
        val satsStr = tokens.getOrNull(7)
        val hdopStr = tokens.getOrNull(8)
        val altStr = tokens.getOrNull(9)

        val quality = qualityStr?.toIntOrNull() ?: 0
        lastGgaQuality = quality

        val lat = parseCoordinate(latStr, latDir)
        val lon = parseCoordinate(lonStr, lonDir)
        val satsUsed = satsStr?.toIntOrNull() ?: currentData.satellitesUsed
        val hdop = hdopStr?.toFloatOrNull() ?: currentData.hdop
        val alt = altStr?.toDoubleOrNull() ?: currentData.altitudeMeters

        val fixType = mapFixType(quality, lastGsaFixMode)
        val is3D = quality == 4 || quality == 5 || quality == 2 || (quality == 1 && (lastGsaFixMode == 3 || (alt != null && satsUsed >= 4)))
        val hasFix = quality > 0

        // Accuracy estimation based on HDOP (~4m baseline * HDOP)
        val accuracy = if (hdop != null && hdop > 0) hdop * 4.0f else currentData.accuracyMeters

        checkBackupFixTime(hasFix)

        val (utcTime, utcDate) = formatUtcDisplay(timeStr, null)

        currentData = currentData.copy(
            hasFix = hasFix,
            fixType = fixType,
            is3DFix = is3D,
            ggaQuality = quality,
            latitude = lat ?: currentData.latitude,
            longitude = lon ?: currentData.longitude,
            altitudeMeters = alt,
            accuracyMeters = accuracy,
            hdop = hdop,
            satellitesUsed = satsUsed,
            timestampUtcMillis = parseTime(timeStr) ?: currentData.timestampUtcMillis,
            utcTimeString = if (utcTime.isNotEmpty()) utcTime else currentData.utcTimeString,
            utcDateString = if (utcDate.isNotEmpty()) utcDate else currentData.utcDateString,
            rawSentence = raw
        )
    }

    private fun parseGSA(tokens: List<String>, raw: String) {
        // $GNGSA,A,3,01,02,03,04,05,06,07,08,09,10,11,12,1.0,1.0,1.0
        val fixModeStr = tokens.getOrNull(2)
        val pdopStr = tokens.getOrNull(15)
        val hdopStr = tokens.getOrNull(16)
        val vdopStr = tokens.getOrNull(17)

        val fixMode = fixModeStr?.toIntOrNull() ?: 1
        lastGsaFixMode = fixMode

        val pdop = pdopStr?.toFloatOrNull() ?: currentData.pdop
        val hdop = hdopStr?.toFloatOrNull() ?: currentData.hdop
        val vdop = vdopStr?.toFloatOrNull() ?: currentData.vdop

        val is3D = fixMode == 3 || (lastGgaQuality in listOf(1, 2, 4, 5))
        val fixType = mapFixType(lastGgaQuality, fixMode)

        currentData = currentData.copy(
            is3DFix = is3D,
            fixType = fixType,
            pdop = pdop,
            hdop = hdop,
            vdop = vdop,
            rawSentence = raw
        )
    }

    private fun parseRMC(tokens: List<String>, raw: String) {
        // $GNRMC,hhmmss.ss,A,llll.ll,a,yyyyy.yy,a,x.x,x.x,ddmmyy,,,a
        val timeStr = tokens.getOrNull(1)
        val status = tokens.getOrNull(2) // A=valid, V=warning
        val latStr = tokens.getOrNull(3)
        val latDir = tokens.getOrNull(4)
        val lonStr = tokens.getOrNull(5)
        val lonDir = tokens.getOrNull(6)
        val speedKnotsStr = tokens.getOrNull(7)
        val trackStr = tokens.getOrNull(8)
        val dateStr = tokens.getOrNull(9)

        val isValid = status.equals("A", ignoreCase = true)
        val lat = parseCoordinate(latStr, latDir)
        val lon = parseCoordinate(lonStr, lonDir)
        val speedKnots = speedKnotsStr?.toDoubleOrNull()
        val speedKmh = speedKnots?.let { it * 1.852 }
        val speedMps = speedKnots?.let { it * 0.514444 }
        val bearing = trackStr?.toFloatOrNull()
        val timestamp = parseDateTime(timeStr, dateStr)

        val (utcTime, utcDate) = formatUtcDisplay(timeStr, dateStr)

        if (isValid) {
            checkBackupFixTime(true)
        }

        val is3D = if (isValid && (lastGsaFixMode == 3 || lastGgaQuality in listOf(1, 2, 4, 5))) true else currentData.is3DFix
        val fixType = if (isValid && currentData.fixType == FixType.NO_FIX) {
            if (is3D) FixType.FIX_3D else FixType.FIX_2D
        } else {
            currentData.fixType
        }

        currentData = currentData.copy(
            hasFix = isValid || currentData.hasFix,
            fixType = fixType,
            is3DFix = is3D,
            latitude = lat ?: currentData.latitude,
            longitude = lon ?: currentData.longitude,
            speedKmh = speedKmh ?: currentData.speedKmh,
            speedMps = speedMps ?: currentData.speedMps,
            bearingDegrees = bearing ?: currentData.bearingDegrees,
            timestampUtcMillis = timestamp ?: currentData.timestampUtcMillis,
            utcTimeString = if (utcTime.isNotEmpty()) utcTime else currentData.utcTimeString,
            utcDateString = if (utcDate.isNotEmpty()) utcDate else currentData.utcDateString,
            rawSentence = raw
        )
    }

    private fun parseVTG(tokens: List<String>, raw: String) {
        // $GNVTG,track,T,track,M,speed_knots,N,speed_kmh,K,mode
        val bearingStr = tokens.getOrNull(1)
        val speedKmhStr = tokens.getOrNull(7)

        val bearing = bearingStr?.toFloatOrNull()
        val speedKmh = speedKmhStr?.toDoubleOrNull()
        val speedMps = speedKmh?.let { it / 3.6 }

        currentData = currentData.copy(
            bearingDegrees = bearing ?: currentData.bearingDegrees,
            speedKmh = speedKmh ?: currentData.speedKmh,
            speedMps = speedMps ?: currentData.speedMps,
            rawSentence = raw
        )
    }

    private fun parseGSV(type: String, tokens: List<String>, raw: String) {
        // $GPGSV,total_msgs,msg_nr,total_sats, prn1,elev1,azim1,snr1, prn2,elev2,azim2,snr2,... , [signal_id]
        val totalMsgs = tokens.getOrNull(1)?.toIntOrNull() ?: 1
        val msgNr = tokens.getOrNull(2)?.toIntOrNull() ?: 1
        val totalSatsInView = tokens.getOrNull(3)?.toIntOrNull() ?: currentData.satellitesInView

        val systemName = when {
            type.startsWith("\$GP") -> "GPS"
            type.startsWith("\$BD") || type.startsWith("\$GB") -> "BDS"
            type.startsWith("\$GL") -> "GLONASS"
            type.startsWith("\$GA") -> "Galileo"
            else -> "Other"
        }

        // Check if signal ID is present or L5 is reported
        val signalId = tokens.lastOrNull()?.trim()
        val isL5 = signalId == "1" || tokens.getOrNull(1) == "1" || (systemName == "GPS" && totalMsgs > 4)
        if (isL5) {
            isL5Detected = true
        }

        val systemMap = satCnoMap.getOrPut(systemName) { mutableMapOf() }
        if (msgNr == 1) {
            systemMap.clear()
        }

        // Parse up to 4 satellites per sentence
        // indices: 4,5,6,7 ; 8,9,10,11 ; 12,13,14,15 ; 16,17,18,19
        for (i in 0 until 4) {
            val prnIndex = 4 + (i * 4)
            val snrIndex = 7 + (i * 4)
            val prn = tokens.getOrNull(prnIndex)?.toIntOrNull()
            val snr = tokens.getOrNull(snrIndex)?.toDoubleOrNull()

            if (prn != null && snr != null && snr > 0) {
                systemMap[prn] = snr
                if (systemName == "GPS") {
                    if (isL5) {
                        l5CnoList.add(snr)
                        if (l5CnoList.size > 20) l5CnoList.removeAt(0)
                    } else {
                        l1CnoList.add(snr)
                        if (l1CnoList.size > 20) l1CnoList.removeAt(0)
                    }
                }
            }
        }

        // Build satellite systems summary
        val systemsSummary = mutableMapOf<String, SatelliteSystemInfo>()
        var totalCnoSum = 0.0
        var totalCnoCount = 0

        for ((sys, map) in satCnoMap) {
            val count = map.size
            val avg = if (count > 0) map.values.average() else null
            systemsSummary[sys] = SatelliteSystemInfo(
                systemName = sys,
                satCount = count,
                avgCno = avg
            )
            map.values.forEach {
                totalCnoSum += it
                totalCnoCount++
            }
        }

        val overallAvg = if (totalCnoCount > 0) totalCnoSum / totalCnoCount else null
        val l1Avg = if (l1CnoList.isNotEmpty()) l1CnoList.average() else null
        val l5Avg = if (l5CnoList.isNotEmpty()) l5CnoList.average() else null

        val updatedAntenna = currentData.antennaStatus.copy(
            isDualBand = isL5Detected,
            l1AvgCno = l1Avg,
            l5AvgCno = l5Avg
        )

        currentData = currentData.copy(
            satellitesInView = totalSatsInView,
            satellitesBySystem = systemsSummary,
            overallAvgCno = overallAvg,
            antennaStatus = updatedAntenna,
            rawSentence = raw
        )
    }

    private fun parseTXT(tokens: List<String>, raw: String) {
        // $GNTXT,04,01,04,INS,<dr>,<install>,...
        // $GNTXT,01,01,02,ANT_OK,...
        val insIndex = tokens.indexOfFirst { it.equals("INS", ignoreCase = true) }
        if (insIndex != -1 && tokens.size > insIndex + 1) {
            val drStr = tokens.getOrNull(insIndex + 1)?.uppercase(Locale.ROOT) ?: ""
            val installStr = tokens.getOrNull(insIndex + 2)?.uppercase(Locale.ROOT) ?: ""

            val drState = when (drStr) {
                "V" -> InsDrState.OFF
                "G" -> InsDrState.GNSS_ONLY
                "E" -> InsDrState.CALIBRATING
                "A" -> InsDrState.ACTIVE
                else -> InsDrState.UNKNOWN
            }

            val installState = when (installStr) {
                "0" -> InsInstallState.DETECTING
                "1" -> InsInstallState.ROLL_READY
                "2" -> InsInstallState.YAW_READY
                "3" -> InsInstallState.FULL_READY
                "4", "5", "6" -> InsInstallState.ERROR
                else -> InsInstallState.UNKNOWN
            }

            val recommendation = when {
                drStr == "V" -> "Начните движение для калибровки"
                drStr == "G" && installStr == "1" -> "Разгонитесь >40 км/ч, выполните повороты 90°"
                drStr == "G" && installStr == "2" -> "Продолжайте движение 15–20 минут"
                drStr == "A" && installStr == "3" -> "✅ INS полностью готов к работе"
                installStr in listOf("4", "5", "6") -> "🔴 Сбросьте калибровку (команда RESET) и повторите"
                drStr == "E" -> "Идёт оценка параметров калибровки"
                drStr == "A" -> "✅ INS активен"
                else -> "Ожидание калибровки..."
            }

            currentData = currentData.copy(
                insStatus = InsStatus(
                    drState = drState,
                    installState = installState,
                    rawDr = drStr,
                    rawInstall = installStr,
                    recommendation = recommendation,
                    lastUpdatedMillis = System.currentTimeMillis()
                ),
                rawSentence = raw
            )
            return
        }

        // Antenna check: ANT_OK, ANT_OPEN, ANT_SHORT
        val antToken = tokens.firstOrNull { it.startsWith("ANT_", ignoreCase = true) }
        if (antToken != null) {
            val antState = when {
                antToken.contains("OK", ignoreCase = true) -> AntennaState.OK
                antToken.contains("OPEN", ignoreCase = true) -> AntennaState.OPEN
                antToken.contains("SHORT", ignoreCase = true) -> AntennaState.SHORT
                else -> AntennaState.UNKNOWN
            }

            currentData = currentData.copy(
                antennaStatus = currentData.antennaStatus.copy(
                    state = antState,
                    lastUpdatedMillis = System.currentTimeMillis()
                ),
                rawSentence = raw
            )
            return
        }

        currentData = currentData.copy(rawSentence = raw)
    }

    private fun checkBackupFixTime(hasFix: Boolean) {
        if (!hasFix || firstFixRecorded || connectionStartTime <= 0L) return

        firstFixRecorded = true
        val elapsedSec = (System.currentTimeMillis() - connectionStartTime) / 1000
        val backupState = when {
            elapsedSec < 5 -> BackupState.HOT_START
            elapsedSec in 5..60 -> BackupState.WARM_START
            else -> BackupState.COLD_START
        }

        currentData = currentData.copy(
            backupPpsStatus = currentData.backupPpsStatus.copy(
                timeToFirstFixSeconds = elapsedSec,
                backupState = backupState
            )
        )
    }

    private fun parseCoordinate(coordStr: String?, dir: String?): Double? {
        if (coordStr.isNullOrBlank() || dir.isNullOrBlank()) return null
        val value = coordStr.toDoubleOrNull() ?: return null

        val degrees = floor(value / 100.0)
        val minutes = value - (degrees * 100.0)
        var decimal = degrees + (minutes / 60.0)

        if (dir.equals("S", ignoreCase = true) || dir.equals("W", ignoreCase = true)) {
            decimal = -decimal
        }
        return decimal
    }

    private fun parseTime(timeStr: String?): Long? {
        if (timeStr.isNullOrBlank() || timeStr.length < 6) return null
        return try {
            val now = System.currentTimeMillis()
            val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val todayStr = dayFormat.format(now)
            val fullTimeStr = todayStr + timeStr.substring(0, 6)
            val fullFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            fullFormat.parse(fullTimeStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDateTime(timeStr: String?, dateStr: String?): Long? {
        if (timeStr.isNullOrBlank() || dateStr.isNullOrBlank() || timeStr.length < 6 || dateStr.length < 6) {
            return parseTime(timeStr)
        }
        return try {
            val dateTimeStr = dateStr.substring(0, 6) + timeStr.substring(0, 6)
            val format = SimpleDateFormat("ddMMyyHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(dateTimeStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun formatUtcDisplay(timeStr: String?, dateStr: String?): Pair<String, String> {
        val timeFormatted = if (!timeStr.isNullOrBlank() && timeStr.length >= 6) {
            val hh = timeStr.substring(0, 2)
            val mm = timeStr.substring(2, 4)
            val ss = timeStr.substring(4, 6)
            "$hh:$mm:$ss UTC"
        } else {
            ""
        }

        val dateFormatted = if (!dateStr.isNullOrBlank() && dateStr.length >= 6) {
            val dd = dateStr.substring(0, 2)
            val mm = dateStr.substring(2, 4)
            val yy = dateStr.substring(4, 6)
            "$dd.$mm.20$yy"
        } else {
            ""
        }

        return Pair(timeFormatted, dateFormatted)
    }

    private fun mapFixType(ggaQuality: Int, gsaMode: Int): FixType {
        return when (ggaQuality) {
            4 -> FixType.RTK_FIXED
            5 -> FixType.RTK_FLOAT
            2 -> FixType.DGPS
            1 -> FixType.FIX_3D
            0 -> if (gsaMode == 3) FixType.FIX_3D else if (gsaMode == 2) FixType.FIX_2D else FixType.NO_FIX
            else -> when (gsaMode) {
                3 -> FixType.FIX_3D
                2 -> FixType.FIX_2D
                else -> FixType.NO_FIX
            }
        }
    }

    fun getCurrentData(): GpsData = currentData

    fun reset() {
        currentData = GpsData()
        lastGsaFixMode = 1
        lastGgaQuality = 0
        satCnoMap.clear()
        l1CnoList.clear()
        l5CnoList.clear()
        isL5Detected = false
        firstFixRecorded = false
        connectionStartTime = 0L
    }
}
