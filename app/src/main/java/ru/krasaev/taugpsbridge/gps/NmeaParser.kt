package ru.krasaev.taugpsbridge.gps

import ru.krasaev.taugpsbridge.model.AntennaState
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

    // ─── Состояние ───
    private var currentData = GpsData()
    private var lastGsaFixMode: Int = 1     // 1 = no fix, 2 = 2D, 3 = 3D
    private var lastGgaQuality: Int = 0
    private var connectionStartTime: Long = 0L
    private var firstFixRecorded: Boolean = false

    // C/N0 по диапазонам: "GPS_L1", "GPS_L5", "BDS_B1", "BDS_B2a", ...
    private val cnoByBand = mutableMapOf<String, MutableMap<Int, Double>>()
    private val bandAverages = mutableMapOf<String, Double?>()

    // ═══════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════

    fun onConnected() {
        reset()
        connectionStartTime = System.currentTimeMillis()
        firstFixRecorded = false
        currentData = currentData.copy(
            backupPpsStatus = BackupPpsStatus(backupState = BackupState.WAITING)
        )
    }

    fun reset() {
        currentData = GpsData()
        lastGsaFixMode = 1
        lastGgaQuality = 0
        cnoByBand.clear()
        bandAverages.clear()
        firstFixRecorded = false
        connectionStartTime = 0L
    }

    fun getCurrentData(): GpsData = currentData

    // ═══════════════════════════════════════════════════════
    //  Main entry
    // ═══════════════════════════════════════════════════════

    fun parseSentence(sentence: String): GpsData {
        val trimmed = sentence.trim()
        if (!trimmed.startsWith("$") && !trimmed.startsWith("!")) return currentData

        val payload = trimmed.substringBefore("*")
        val tokens = payload.split(",")
        if (tokens.isEmpty()) return currentData

        // PPS-индикатор обновляется на любой валидной строке
        currentData = currentData.copy(
            backupPpsStatus = currentData.backupPpsStatus.copy(
                isPpsActive = true,
                lastPpsTimestampMillis = System.currentTimeMillis()
            )
        )

        try {
            val type = tokens[0].uppercase(Locale.ROOT)
            when {
                type.endsWith("GGA") -> parseGGA(tokens, trimmed)
                type.endsWith("GSA") -> parseGSA(tokens, trimmed)
                type.endsWith("RMC") -> parseRMC(tokens, trimmed)
                type.endsWith("VTG") -> parseVTG(tokens, trimmed)
                type.endsWith("GSV") -> parseGSV(type, tokens, trimmed)
                type.endsWith("TXT") -> parseTXT(tokens, trimmed)
                else -> currentData = currentData.copy(rawSentence = trimmed)
            }
        } catch (_: Exception) {
            // повреждённая строка — пропускаем
        }
        return currentData
    }

    // ═══════════════════════════════════════════════════════
    //  Binary MON-VER
    // ═══════════════════════════════════════════════════════

    fun parseBinaryVersionResponse(data: ByteArray): ModuleInfo? {
        // F1 D9 0A 04 20 00 [16B SW] [16B HW] CK1 CK2
        if (data.size < 38) return null

        val sw = cleanString(data.copyOfRange(6, minOf(22, data.size)))
        val hw = cleanString(data.copyOfRange(22, minOf(38, data.size)))

        // Двухчастотность из SW: "HD8040DF..." → DF = Dual Frequency
        val isDualFromSw = when {
            sw.contains("DF", ignoreCase = true) -> true
            sw.contains("SF", ignoreCase = true) -> false
            else -> null
        }

        // Двухчастотность из HW (если заполнено)
        val isDualFromHw = when {
            hw.contains("1216A00", ignoreCase = true) -> true
            hw.contains("1216AFX", ignoreCase = true) -> false
            else -> null
        }

        // Не сбрасываем ранее подтверждённое значение (если уже узнали по GSV)
        val dual = isDualFromHw
            ?: isDualFromSw
            ?: currentData.moduleInfo.isDualFrequency

        val info = ModuleInfo(
            swVersion = sw,
            hwVersion = hw,
            isDualFrequency = dual,
            isDetected = true
        )
        currentData = currentData.copy(moduleInfo = info)
        return info
    }

    // ═══════════════════════════════════════════════════════
    //  NMEA Parsers
    // ═══════════════════════════════════════════════════════

    private fun parseGGA(tokens: List<String>, raw: String) {
        val timeStr = tokens.token(1)
        val quality = tokens.toInt(6) ?: 0
        lastGgaQuality = quality

        val lat = parseCoordinate(tokens.token(2), tokens.token(3))
        val lon = parseCoordinate(tokens.token(4), tokens.token(5))
        val satsUsed = tokens.toInt(7) ?: currentData.satellitesUsed
        val hdop = tokens.toFloat(8) ?: currentData.hdop
        val alt = tokens.toDouble(9) ?: currentData.altitudeMeters

        val (fixType, is3D) = determineFix()
        val hasFix = quality > 0
        val accuracy = if (hdop != null && hdop > 0) hdop * 4.0f else currentData.accuracyMeters

        checkBackupFixTime(hasFix)

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
            timestampUtcMillis = parseTimeOnly(timeStr) ?: currentData.timestampUtcMillis,
            utcTimeString = formatUtcTime(timeStr).ifEmpty { currentData.utcTimeString },
            rawSentence = raw
        )
    }

    private fun parseGSA(tokens: List<String>, raw: String) {
        lastGsaFixMode = tokens.toInt(2) ?: 1
        val (fixType, is3D) = determineFix()

        currentData = currentData.copy(
            is3DFix = is3D,
            fixType = fixType,
            pdop = tokens.toFloat(15) ?: currentData.pdop,
            hdop = tokens.toFloat(16) ?: currentData.hdop,
            vdop = tokens.toFloat(17) ?: currentData.vdop,
            rawSentence = raw
        )
    }

    private fun parseRMC(tokens: List<String>, raw: String) {
        val timeStr = tokens.token(1)
        val status = tokens.token(2)
        val dateStr = tokens.token(9)
        val isValid = status.equals("A", ignoreCase = true)

        val lat = parseCoordinate(tokens.token(3), tokens.token(4))
        val lon = parseCoordinate(tokens.token(5), tokens.token(6))
        val speedKnots = tokens.toDouble(7)
        val bearing = tokens.toFloat(8)

        val speedKmh = speedKnots?.times(1.852)
        val speedMps = speedKnots?.times(0.514444)

        val (fixType, is3D) = determineFix()

        if (isValid) checkBackupFixTime(true)

        currentData = currentData.copy(
            hasFix = isValid || currentData.hasFix,
            fixType = fixType,
            is3DFix = is3D,
            latitude = lat ?: currentData.latitude,
            longitude = lon ?: currentData.longitude,
            speedKmh = speedKmh ?: currentData.speedKmh,
            speedMps = speedMps ?: currentData.speedMps,
            bearingDegrees = bearing ?: currentData.bearingDegrees,
            timestampUtcMillis = parseDateTime(timeStr, dateStr) ?: currentData.timestampUtcMillis,
            utcTimeString = formatUtcTime(timeStr).ifEmpty { currentData.utcTimeString },
            utcDateString = formatUtcDate(dateStr).ifEmpty { currentData.utcDateString },
            rawSentence = raw
        )
    }

    private fun parseVTG(tokens: List<String>, raw: String) {
        val bearing = tokens.toFloat(1)
        val speedKmh = tokens.toDouble(7)
        val speedMps = speedKmh?.div(3.6)

        currentData = currentData.copy(
            bearingDegrees = bearing ?: currentData.bearingDegrees,
            speedKmh = speedKmh ?: currentData.speedKmh,
            speedMps = speedMps ?: currentData.speedMps,
            rawSentence = raw
        )
    }

    // ─── GSV ───
    private fun parseGSV(type: String, tokens: List<String>, raw: String) {
        val msgNr = tokens.toInt(2) ?: 1

        val system = resolveSystem(type)
        val signalId = detectSignalId(tokens)
        val band = resolveBand(system, signalId)
        val bandKey = "${system}_$band"

        if (msgNr == 1) cnoByBand[bandKey] = mutableMapOf()
        val bandMap = cnoByBand.getOrPut(bandKey) { mutableMapOf() }

        // Парсим до 4 спутников: prn, elev, azim, cn0
        for (i in 0 until 4) {
            val prn = tokens.toInt(4 + i * 4) ?: continue
            val snr = tokens.toDouble(7 + i * 4) ?: continue
            if (prn > 0 && snr > 0) bandMap[prn] = snr
        }

        rebuildBandAverages()

        // ─── L1 и L5 средние по всем системам ───
        val l1Avg = averageAcrossBands(listOf("L1", "B1", "E1"))
        val l5Avg = averageAcrossBands(listOf("L5", "B2a", "E5a"))

        // Двухчастотность: наличие любой secondary band
        val dualDetected = cnoByBand.keys.any { key ->
            key.endsWith("_L5") || key.endsWith("_B2a") || key.endsWith("_E5a")
        }

        val newModuleInfo = if (dualDetected && currentData.moduleInfo.isDualFrequency != true) {
            currentData.moduleInfo.copy(isDualFrequency = true)
        } else {
            currentData.moduleInfo
        }

        currentData = currentData.copy(
            satellitesInView = computeSatellitesInView(),
            satellitesBySystem = buildSystemsSummary(),
            overallAvgCno = computeOverallAvgCno(),
            antennaStatus = currentData.antennaStatus.copy(
                isDualBand = dualDetected,
                l1AvgCno = l1Avg,
                l5AvgCno = l5Avg,
                lastUpdatedMillis = System.currentTimeMillis()
            ),
            moduleInfo = newModuleInfo,
            rawSentence = raw
        )
    }

    // ─── TXT ───
    private fun parseTXT(tokens: List<String>, raw: String) {
        val insIndex = tokens.indexOfFirst { it.equals("INS", ignoreCase = true) }
        if (insIndex != -1 && tokens.size > insIndex + 1) {
            val drStr = tokens.getOrNull(insIndex + 1)?.uppercase(Locale.ROOT) ?: ""
            val installStr = tokens.getOrNull(insIndex + 2)?.uppercase(Locale.ROOT) ?: ""

            currentData = currentData.copy(
                insStatus = InsStatus(
                    drState = mapDrState(drStr),
                    installState = mapInstallState(installStr),
                    rawDr = drStr,
                    rawInstall = installStr,
                    recommendation = buildRecommendation(drStr, installStr),
                    lastUpdatedMillis = System.currentTimeMillis()
                ),
                rawSentence = raw
            )
            return
        }

        // ANT_OK / ANT_OPEN / ANT_SHORT / ANT_SAT
        val antToken = tokens.firstOrNull { it.startsWith("ANT_", ignoreCase = true) }
        if (antToken != null) {
            val state = when {
                antToken.contains("SHORT", ignoreCase = true) -> AntennaState.SHORT
                antToken.contains("OPEN", ignoreCase = true) -> AntennaState.OPEN
                antToken.contains("OK", ignoreCase = true) -> AntennaState.OK
                else -> AntennaState.UNKNOWN
            }
            currentData = currentData.copy(
                antennaStatus = currentData.antennaStatus.copy(
                    state = state,
                    lastUpdatedMillis = System.currentTimeMillis()
                ),
                rawSentence = raw
            )
            return
        }

        currentData = currentData.copy(rawSentence = raw)
    }

    // ═══════════════════════════════════════════════════════
    //  Fix determination
    // ═══════════════════════════════════════════════════════

    private fun determineFix(): Pair<FixType, Boolean> {
        val fixType = when (lastGgaQuality) {
            4 -> FixType.RTK_FIXED
            5 -> FixType.RTK_FLOAT
            2 -> FixType.DGPS
            1 -> FixType.FIX_3D
            else -> when (lastGsaFixMode) {
                3 -> FixType.FIX_3D
                2 -> FixType.FIX_2D
                else -> FixType.NO_FIX
            }
        }
        val is3D = fixType in setOf(
            FixType.RTK_FIXED, FixType.RTK_FLOAT, FixType.DGPS, FixType.FIX_3D
        )
        return fixType to is3D
    }

    // ═══════════════════════════════════════════════════════
    //  GSV helpers
    // ═══════════════════════════════════════════════════════

    private fun resolveSystem(type: String): String {
        val body = type.removePrefix("$")
        return when {
            body.startsWith("GP") -> "GPS"
            body.startsWith("BD") || body.startsWith("GB") -> "BDS"
            body.startsWith("GL") -> "GLO"
            body.startsWith("GA") -> "GAL"
            body.startsWith("GQ") -> "QZSS"
            else -> "UNKNOWN"
        }
    }

    private fun detectSignalId(tokens: List<String>): Int? {
        if (tokens.size < 5) return null
        // Signal ID есть, только если (size - 4) % 4 == 1
        if ((tokens.size - 4) % 4 != 1) return null
        val candidate = tokens.last().trim().toIntOrNull() ?: return null
        return if (candidate in 0..15) candidate else null
    }

    /**
     * NMEA 4.11 signal ID.
     *
     * GPS:  1=L1C/A, 2=L1P, 3=L1M, 4=L2P, 5=L2C-M, 6=L2C-L, 7=L5-I, 8=L5-Q
     * BDS:  1=B1I, 2=B1Q, 3=B1C, 4=B1A, 5=B2I, 6=B2Q, 7=B2a, 8=B2b, 9=B3I
     * GAL:  1=E5a, 2=E5b, 3=E5a+b, 4=E6-A, 5=E6-BC, 6=L1-A, 7=L1-BC
     * GLO:  всегда L1
     * QZSS: как у GPS
     */
    private fun resolveBand(system: String, signalId: Int?): String = when (system) {
        "GPS" -> when (signalId) {
            5, 6 -> "L2C"      // L2C-M, L2C-L
            7, 8 -> "L5"       // L5-I, L5-Q
            else -> "L1"       // 1 = L1 C/A
        }
        "BDS" -> when (signalId) {
            5, 6, 7, 8 -> "B2a"  // B2I/B2Q/B2a/B2b → все secondary
            9 -> "B3I"
            else -> "B1"         // 1 = B1I
        }
        "GLO" -> "L1"
        "GAL" -> when (signalId) {
            1, 2, 3 -> "E5a"
            4, 5 -> "E6"
            6, 7 -> "E1"
            else -> "E1"
        }
        "QZSS" -> when (signalId) {
            7, 8 -> "L5"
            else -> "L1"
        }
        else -> "UNKNOWN"
    }

    private fun rebuildBandAverages() {
        bandAverages.clear()
        for ((key, map) in cnoByBand) {
            bandAverages[key] = if (map.isNotEmpty()) map.values.average() else null
        }
    }

    /** Средний C/N0 по всем системам для заданных бэндов. */
    private fun averageAcrossBands(bands: List<String>): Double? {
        var sum = 0.0
        var cnt = 0
        for ((key, avg) in bandAverages) {
            val band = key.substringAfter("_", "")
            if (band in bands && avg != null) {
                sum += avg
                cnt++
            }
        }
        return if (cnt > 0) sum / cnt else null
    }

    /**
     * Ключ = "GPS_L1", "BDS_B2a" и т.д. UI может распарсить через substringBefore/After("_").
     */
    private fun buildSystemsSummary(): Map<String, SatelliteSystemInfo> {
        val result = mutableMapOf<String, SatelliteSystemInfo>()
        for ((key, map) in cnoByBand) {
            result[key] = SatelliteSystemInfo(
                systemName = key,
                satCount = map.size,
                avgCno = if (map.isNotEmpty()) map.values.average() else null
            )
        }
        return result
    }

    private fun computeOverallAvgCno(): Double? {
        var sum = 0.0
        var cnt = 0
        for (map in cnoByBand.values) {
            for (v in map.values) { sum += v; cnt++ }
        }
        return if (cnt > 0) sum / cnt else null
    }

    /** Только primary bands — без двойного счёта L5/B2a. */
    private fun computeSatellitesInView(): Int =
        cnoByBand
            .filterKeys { it.endsWith("_L1") || it.endsWith("_B1") || it.endsWith("_E1") }
            .values.sumOf { it.size }

    // ═══════════════════════════════════════════════════════
    //  TXT helpers
    // ═══════════════════════════════════════════════════════

    private fun mapDrState(s: String) = when (s) {
        "V" -> InsDrState.OFF
        "G" -> InsDrState.GNSS_ONLY
        "E" -> InsDrState.CALIBRATING
        "A" -> InsDrState.ACTIVE
        else -> InsDrState.UNKNOWN
    }

    private fun mapInstallState(s: String) = when (s) {
        "0" -> InsInstallState.DETECTING
        "1" -> InsInstallState.ROLL_READY
        "2" -> InsInstallState.YAW_READY
        "3" -> InsInstallState.FULL_READY
        "4", "5", "6" -> InsInstallState.ERROR
        else -> InsInstallState.UNKNOWN
    }

    private fun buildRecommendation(dr: String, install: String): String = when {
        dr == "V" -> "Начните движение для калибровки"
        dr == "G" && install == "1" -> "Разгонитесь >40 км/ч, выполните повороты 90°"
        dr == "G" && install == "2" -> "Продолжайте движение 15–20 минут"
        dr == "A" && install == "3" -> "✅ INS полностью готов к работе"
        install in listOf("4", "5", "6") -> "🔴 Сбросьте калибровку (RESET) и повторите"
        dr == "E" -> "Идёт оценка параметров калибровки"
        dr == "A" -> "✅ INS активен"
        else -> "Ожидание калибровки..."
    }

    // ═══════════════════════════════════════════════════════
    //  Backup timing
    // ═══════════════════════════════════════════════════════

    private fun checkBackupFixTime(hasFix: Boolean) {
        if (!hasFix || firstFixRecorded || connectionStartTime <= 0L) return

        firstFixRecorded = true
        val elapsedSec = (System.currentTimeMillis() - connectionStartTime) / 1000
        val state = when {
            elapsedSec < 5 -> BackupState.HOT_START
            elapsedSec in 5..60 -> BackupState.WARM_START
            else -> BackupState.COLD_START
        }

        currentData = currentData.copy(
            backupPpsStatus = currentData.backupPpsStatus.copy(
                timeToFirstFixSeconds = elapsedSec,
                backupState = state
            )
        )
    }

    // ═══════════════════════════════════════════════════════
    //  Token extensions
    // ═══════════════════════════════════════════════════════

    private fun List<String>.token(i: Int): String? = getOrNull(i)?.trim()
    private fun List<String>.toInt(i: Int): Int? = getOrNull(i)?.trim()?.toIntOrNull()
    private fun List<String>.toDouble(i: Int): Double? = getOrNull(i)?.trim()?.toDoubleOrNull()
    private fun List<String>.toFloat(i: Int): Float? = getOrNull(i)?.trim()?.toFloatOrNull()

    // ═══════════════════════════════════════════════════════
    //  Coordinates & time
    // ═══════════════════════════════════════════════════════

    private fun parseCoordinate(coordStr: String?, dir: String?): Double? {
        if (coordStr.isNullOrBlank() || dir.isNullOrBlank()) return null
        val value = coordStr.toDoubleOrNull() ?: return null
        val degrees = floor(value / 100.0)
        val minutes = value - degrees * 100.0
        val decimal = degrees + minutes / 60.0
        return if (dir.equals("S", true) || dir.equals("W", true)) -decimal else decimal
    }

    private fun parseTimeOnly(timeStr: String?): Long? {
        if (timeStr.isNullOrBlank() || timeStr.length < 6) return null
        return try {
            val utc = TimeZone.getTimeZone("UTC")
            val today = SimpleDateFormat("yyyyMMdd", Locale.US)
                .apply { timeZone = utc }
                .format(System.currentTimeMillis())
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                .apply { timeZone = utc }
                .parse(today + timeStr.substring(0, 6))
                ?.time
        } catch (_: Exception) { null }
    }

    private fun parseDateTime(timeStr: String?, dateStr: String?): Long? {
        if (timeStr.isNullOrBlank() || dateStr.isNullOrBlank() ||
            timeStr.length < 6 || dateStr.length < 6
        ) return parseTimeOnly(timeStr)

        return try {
            SimpleDateFormat("ddMMyyHHmmss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(dateStr.substring(0, 6) + timeStr.substring(0, 6))
                ?.time
        } catch (_: Exception) { null }
    }

    private fun formatUtcTime(timeStr: String?): String {
        if (timeStr.isNullOrBlank() || timeStr.length < 6) return ""
        return "${timeStr.substring(0, 2)}:${timeStr.substring(2, 4)}:${timeStr.substring(4, 6)} UTC"
    }

    private fun formatUtcDate(dateStr: String?): String {
        if (dateStr.isNullOrBlank() || dateStr.length < 6) return ""
        return "${dateStr.substring(0, 2)}.${dateStr.substring(2, 4)}.20${dateStr.substring(4, 6)}"
    }

    private fun cleanString(bytes: ByteArray): String =
        String(bytes, Charsets.US_ASCII)
            .takeWhile { it.code != 0 }
            .trim()
}