package ru.krasaev.taugpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.krasaev.taugpsbridge.gps.NmeaParser
import ru.krasaev.taugpsbridge.model.AntennaState
import ru.krasaev.taugpsbridge.model.FixType
import ru.krasaev.taugpsbridge.model.InsDrState
import ru.krasaev.taugpsbridge.model.InsInstallState

class NmeaParserTest {

    @Test
    fun testParse3DFixGsaAndGga() {
        val parser = NmeaParser()

        // 1. Initial state
        assertFalse(parser.getCurrentData().is3DFix)
        assertEquals(FixType.NO_FIX, parser.getCurrentData().fixType)

        // 2. Parse GSA with 3D fix (mode 3)
        val gsaSentence = "\$GNGSA,A,3,01,02,03,04,05,06,07,08,09,10,11,12,1.2,0.8,0.9*1A"
        val dataAfterGsa = parser.parseSentence(gsaSentence)
        assertTrue(dataAfterGsa.is3DFix)
        assertEquals(FixType.FIX_3D, dataAfterGsa.fixType)
        assertEquals(0.8f, dataAfterGsa.hdop ?: 0f, 0.001f)

        // 3. Parse GGA with coordinates
        val ggaSentence = "\$GNGGA,123519.00,5545.1234,N,03737.5678,E,1,08,0.9,150.5,M,12.0,M,,*47"
        val dataAfterGga = parser.parseSentence(ggaSentence)

        assertTrue(dataAfterGga.hasFix)
        assertTrue(dataAfterGga.is3DFix)
        assertEquals(8, dataAfterGga.satellitesUsed)
        assertNotNull(dataAfterGga.latitude)
        assertNotNull(dataAfterGga.longitude)
        assertEquals(55.752056, dataAfterGga.latitude ?: 0.0, 0.0001)
        assertEquals(37.62613, dataAfterGga.longitude ?: 0.0, 0.0001)
        assertEquals(150.5, dataAfterGga.altitudeMeters ?: 0.0, 0.01)
    }

    @Test
    fun testParseRtkFix() {
        val parser = NmeaParser()

        val ggaRtkFixed = "\$GNGGA,123519.00,5545.1234,N,03737.5678,E,4,18,0.6,150.5,M,12.0,M,,*47"
        val dataFixed = parser.parseSentence(ggaRtkFixed)
        assertEquals(FixType.RTK_FIXED, dataFixed.fixType)
        assertEquals(4, dataFixed.ggaQuality)

        val ggaRtkFloat = "\$GNGGA,123519.00,5545.1234,N,03737.5678,E,5,14,0.8,150.5,M,12.0,M,,*47"
        val dataFloat = parser.parseSentence(ggaRtkFloat)
        assertEquals(FixType.RTK_FLOAT, dataFloat.fixType)
        assertEquals(5, dataFloat.ggaQuality)
    }

    @Test
    fun testParseRmcAndSpeed() {
        val parser = NmeaParser()

        val rmcSentence = "\$GNRMC,123519.00,A,5545.1234,N,03737.5678,E,22.4,185.0,230324,,,A*6A"
        val data = parser.parseSentence(rmcSentence)

        assertTrue(data.hasFix)
        assertEquals(22.4 * 1.852, data.speedKmh ?: 0.0, 0.01)
        assertEquals(185.0f, data.bearingDegrees ?: 0.0f, 0.01f)
    }

    @Test
    fun testParseInsStatus() {
        val parser = NmeaParser()

        // $GNTXT,04,01,04,INS,A,3,... (INS ACTIVE, FULL READY)
        val txtSentence = "\$GNTXT,04,01,04,INS,A,3,0,0*01"
        val data = parser.parseSentence(txtSentence)

        assertEquals(InsDrState.ACTIVE, data.insStatus.drState)
        assertEquals(InsInstallState.FULL_READY, data.insStatus.installState)
        assertEquals("✅ INS полностью готов к работе", data.insStatus.recommendation)

        // $GNTXT,04,01,04,INS,V,0,... (INS OFF)
        val txtOff = "\$GNTXT,04,01,04,INS,V,0,0,0*01"
        val dataOff = parser.parseSentence(txtOff)
        assertEquals(InsDrState.OFF, dataOff.insStatus.drState)
        assertEquals(InsInstallState.DETECTING, dataOff.insStatus.installState)
        assertEquals("Начните движение для калибровки", dataOff.insStatus.recommendation)
    }

    @Test
    fun testParseAntennaStatus() {
        val parser = NmeaParser()

        val txtAntOk = "\$GNTXT,01,01,02,ANT_OK*44"
        val dataOk = parser.parseSentence(txtAntOk)
        assertEquals(AntennaState.OK, dataOk.antennaStatus.state)

        val txtAntOpen = "\$GNTXT,01,01,02,ANT_OPEN*44"
        val dataOpen = parser.parseSentence(txtAntOpen)
        assertEquals(AntennaState.OPEN, dataOpen.antennaStatus.state)

        val txtAntShort = "\$GNTXT,01,01,02,ANT_SHORT*44"
        val dataShort = parser.parseSentence(txtAntShort)
        assertEquals(AntennaState.SHORT, dataShort.antennaStatus.state)
    }

    @Test
    fun testParseBinaryVersionResponse() {
        val parser = NmeaParser()

        // F1 D9 0A 04 20 00 [16 bytes SW] [16 bytes HW] CK1 CK2
        val packet = ByteArray(40)
        packet[0] = 0xF1.toByte()
        packet[1] = 0xD9.toByte()
        packet[2] = 0x0A.toByte()
        packet[3] = 0x04.toByte()
        packet[4] = 0x20.toByte()
        packet[5] = 0x00.toByte()

        val swText = "ROM 1.0".toByteArray(Charsets.US_ASCII)
        val hwText = "TAU2202-1216A00".toByteArray(Charsets.US_ASCII)

        System.arraycopy(swText, 0, packet, 6, swText.size)
        System.arraycopy(hwText, 0, packet, 22, hwText.size)

        val moduleInfo = parser.parseBinaryVersionResponse(packet)
        assertNotNull(moduleInfo)
        assertEquals("ROM 1.0", moduleInfo?.swVersion)
        assertEquals("TAU2202-1216A00", moduleInfo?.hwVersion)
        assertEquals(true, moduleInfo?.isDualFrequency)
        assertEquals("Двухчастотный (L1+L5 / B1+B2a)", moduleInfo?.typeDescription)
    }

    @Test
    fun testParseSatellitesAndGsv() {
        val parser = NmeaParser()

        val gsvSentence = "\$GPGSV,2,1,08,01,40,045,42,02,30,120,38,03,20,210,35,04,15,300,28*79"
        val data = parser.parseSentence(gsvSentence)

        assertEquals(8, data.satellitesInView)
        val gps = data.satellitesBySystem["GPS"]
        assertNotNull(gps)
        assertEquals(4, gps?.satCount)
        assertTrue((gps?.avgCno ?: 0.0) > 30.0)
    }
}
