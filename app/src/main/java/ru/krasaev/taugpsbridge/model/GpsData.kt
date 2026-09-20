package ru.krasaev.taugpsbridge.model

enum class FixType(val displayName: String) {
    NO_FIX("Нет фикса"),
    FIX_2D("2D Fix"),
    FIX_3D("3D Fix"),
    DGPS("DGPS"),
    RTK_FLOAT("RTK Float"),
    RTK_FIXED("RTK Fixed")
}

data class ModuleInfo(
    val swVersion: String = "",
    val hwVersion: String = "",
    val isDualFrequency: Boolean? = null,
    val isDetected: Boolean = false
) {
    val displayModel: String
        get() = if (hwVersion.isNotBlank()) hwVersion else if (isDetected) "TAU GNSS Module" else "Определение..."

    val displayFirmware: String
        get() = if (swVersion.isNotBlank()) swVersion else if (isDetected) "Unknown" else "Запрос..."

    val typeDescription: String
        get() = when (isDualFrequency) {
            true -> "Двухчастотный (L1+L5 / B1+B2a)"
            false -> "Одночастотный (L1 / B1)"
            null -> "Определение типа..."
        }
}

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
    val lastUpdatedMillis: Long = 0L
)

data class SatelliteSystemInfo(
    val systemName: String,
    val satCount: Int = 0,
    val avgCno: Double? = null
)

enum class BackupState(val title: String) {
    HOT_START("Backup работает (горячий старт)"),
    WARM_START("Тёплый старт (батарейка слабая)"),
    COLD_START("Холодный старт (нет батарейки)"),
    WAITING("Ожидание первого фикса..."),
    UNKNOWN("Не определено")
}

data class BackupPpsStatus(
    val timeToFirstFixSeconds: Long? = null,
    val backupState: BackupState = BackupState.WAITING,
    val isPpsActive: Boolean = false,
    val lastPpsTimestampMillis: Long = 0L
)

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
    val timestampUtcMillis: Long = 0L,
    val utcTimeString: String = "",
    val utcDateString: String = "",
    val moduleInfo: ModuleInfo = ModuleInfo(),
    val insStatus: InsStatus = InsStatus(),
    val antennaStatus: AntennaStatus = AntennaStatus(),
    val satellitesBySystem: Map<String, SatelliteSystemInfo> = emptyMap(),
    val overallAvgCno: Double? = null,
    val backupPpsStatus: BackupPpsStatus = BackupPpsStatus(),
    val rawSentence: String = ""
)

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
    val displayName: String
        get() {
            val name = productName ?: manufacturerName ?: deviceName
            return "$name (VID: ${vendorId.toString(16).padStart(4, '0')}, PID: ${productId.toString(16).padStart(4, '0')})"
        }

    val uniqueKey: String
        get() = "${vendorId}:${productId}:${deviceName}"
}

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    data class Connected(val deviceName: String, val baudRate: Int) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}
