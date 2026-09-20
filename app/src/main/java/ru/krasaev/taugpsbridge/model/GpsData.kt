package ru.krasaev.taugpsbridge.model

// ═══════════════════════════════════════════════════════════════
//  GNSS Fix
// ═══════════════════════════════════════════════════════════════

enum class FixType(val displayName: String) {
    NO_FIX("Нет фикса"),
    FIX_2D("2D Fix"),
    FIX_3D("3D Fix"),
    DGPS("DGPS"),
    RTK_FLOAT("RTK Float"),
    RTK_FIXED("RTK Fixed")
}

// ═══════════════════════════════════════════════════════════════
//  Module info
// ═══════════════════════════════════════════════════════════════

data class ModuleInfo(
    val swVersion: String = "",
    val hwVersion: String = "",
    val isDualFrequency: Boolean? = null,
    val isDetected: Boolean = false
) {
    /** ID чипа (например, HD8040DF.017747a). */
    val displayChip: String
        get() = hwVersion.ifBlank {
            if (isDetected) "HD8040 (unknown revision)" else "Определение..."
        }

    /** Версия прошивки (например, 3.M8C.4e08c7). */
    val displayFirmware: String
        get() = swVersion.ifBlank {
            if (isDetected) "Unknown" else "Запрос..."
        }

    /** Описание типа приёма. */
    val typeDescription: String
        get() = when (isDualFrequency) {
            true -> "Двухчастотный (L1+L5 / B1+B2a)"
            false -> "Одночастотный (L1 / B1)"
            null -> "Определение типа..."
        }
}

// ═══════════════════════════════════════════════════════════════
//  INS
// ═══════════════════════════════════════════════════════════════

enum class InsDrState(val code: String, val title: String) {
    OFF("V", "INS выключен"),
    GNSS_ONLY("G", "Только GNSS"),
    CALIBRATING("E", "Идёт оценка параметров"),
    ACTIVE("A", "INS АКТИВЕН"),
    UNKNOWN("", "Не определено")
}

enum class InsInstallState(val code: String, val title: String) {
    DETECTING("0", "Определение установки"),
    ROLL_READY("1", "ROLL готов"),
    YAW_READY("2", "YAW готов"),
    FULL_READY("3", "ROLL + YAW готовы"),
    ERROR("4", "Ошибка калибровки"),
    UNKNOWN("", "Не определено")
}

data class InsStatus(
    val drState: InsDrState = InsDrState.UNKNOWN,
    val installState: InsInstallState = InsInstallState.UNKNOWN,
    val rawDr: String = "",
    val rawInstall: String = "",
    val recommendation: String = "Ожидание данных INS...",
    val lastUpdatedMillis: Long = 0L
)

// ═══════════════════════════════════════════════════════════════
//  Antenna
// ═══════════════════════════════════════════════════════════════

enum class AntennaState(val title: String) {
    OK("Активная антенна, OK"),
    OPEN("Антенна не подключена / пассивная"),
    SHORT("Короткое замыкание в антенне!"),
    UNKNOWN("Не определено")
}

data class AntennaStatus(
    val state: AntennaState = AntennaState.UNKNOWN,
    val isDualBand: Boolean = false,
    val l1AvgCno: Double? = null,
    val l5AvgCno: Double? = null,
    val rawL1SatCount: Int = 0,
    val rawL5SatCount: Int = 0,
    val lastUpdatedMillis: Long = 0L
)

// ═══════════════════════════════════════════════════════════════
//  Satellites
// ═══════════════════════════════════════════════════════════════

/**
 * Информация по диапазону одной системы.
 * Ключ [systemName] имеет вид "GPS_L1", "BDS_B2a", "GLO_L1", "GAL_E1", "QZSS_L5".
 */
data class SatelliteSystemInfo(
    val systemName: String,
    val satCount: Int = 0,
    val avgCno: Double? = null
) {
    /** "GPS", "BDS", "GLO", "GAL", "QZSS". */
    val system: String get() = systemName.substringBefore("_")

    /** "L1", "L5", "B1", "B2a", "E1". Пусто, если ключ без "_". */
    val band: String get() = systemName.substringAfter("_", "")
}

// ═══════════════════════════════════════════════════════════════
//  Backup / PPS
// ═══════════════════════════════════════════════════════════════

enum class BackupState(val title: String) {
    HOT_START("Backup работает (горячий старт)"),
    WARM_START("Тёплый старт"),
    COLD_START("Холодный старт"),
    WAITING("Ожидание первого фикса..."),
    UNKNOWN("Не определено")
}

data class BackupPpsStatus(
    val timeToFirstFixSeconds: Long? = null,
    val backupState: BackupState = BackupState.WAITING,
    val isPpsActive: Boolean = false,
    val lastPpsTimestampMillis: Long = 0L
)

// ═══════════════════════════════════════════════════════════════
//  GPS Data
// ═══════════════════════════════════════════════════════════════

data class GpsData(
    val hasFix: Boolean = false,
    val fixType: FixType = FixType.NO_FIX,
    val is3DFix: Boolean = false,
    val ggaQuality: Int = 0,

    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,

    val speedKmh: Double? = null,
    val speedMps: Double? = null,
    val bearingDegrees: Float? = null,

    val accuracyMeters: Float? = null,
    val hdop: Float? = null,
    val vdop: Float? = null,
    val pdop: Float? = null,

    val satellitesUsed: Int = 0,
    val satellitesInView: Int = 0,
    val satellitesBySystem: Map<String, SatelliteSystemInfo> = emptyMap(),
    val overallAvgCno: Double? = null,

    val timestampUtcMillis: Long = 0L,
    val utcTimeString: String = "",
    val utcDateString: String = "",

    val moduleInfo: ModuleInfo = ModuleInfo(),
    val insStatus: InsStatus = InsStatus(),
    val antennaStatus: AntennaStatus = AntennaStatus(),
    val backupPpsStatus: BackupPpsStatus = BackupPpsStatus(),

    val rawSentence: String = ""
)

// ═══════════════════════════════════════════════════════════════
//  Connection
// ═══════════════════════════════════════════════════════════════

enum class ConnectionType(val title: String) {
    USB("USB"),
    BLUETOOTH("Bluetooth")
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val isBonded: Boolean = true
) {
    val displayName: String
        get() = if (name.isNotBlank()) "$name ($address)" else address
}

data class UsbDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val portCount: Int,
    val hasPermission: Boolean
) {
    /**
     * Человекочитаемое имя без VID/PID.
     * VID/PID показывайте отдельно через [vidPidString].
     */
    val displayName: String
        get() = productName?.takeIf { it.isNotBlank() }
            ?: manufacturerName?.takeIf { it.isNotBlank() }
            ?: "USB-устройство"

    /** Строка "VID:10c4 PID:ea60" для subtitle. */
    val vidPidString: String
        get() = "VID:${vendorId.toString(16).padStart(4, '0')} " +
                "PID:${productId.toString(16).padStart(4, '0')}"

    /** Компактный ключ "vid:pid" — fallback для авто-подключения. */
    val vidPidKey: String
        get() = "$vendorId:$productId"

    /**
     * Уникальный ключ для авто-подключения.
     * Приоритет: серийник → vid:pid:последний_сегмент_пути.
     */
    val uniqueKey: String
        get() = if (!serialNumber.isNullOrBlank()) {
            "$vendorId:$productId:$serialNumber"
        } else {
            "$vendorId:$productId:${deviceName.substringAfterLast('/')}"
        }
}

// ═══════════════════════════════════════════════════════════════
//  Connection status
// ═══════════════════════════════════════════════════════════════

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    data class Connected(val deviceName: String, val baudRate: Int) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}