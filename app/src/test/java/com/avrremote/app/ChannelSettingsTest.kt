package com.avrremote.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSettingsTest {

    @Test
    fun testParseDenonLevel() {
        assertEquals(0.0f, ChannelSettingsParser.parseDenonLevel("50")!!, 0.01f)
        assertEquals(3.0f, ChannelSettingsParser.parseDenonLevel("53")!!, 0.01f)
        assertEquals(-3.0f, ChannelSettingsParser.parseDenonLevel("47")!!, 0.01f)
        assertEquals(-12.0f, ChannelSettingsParser.parseDenonLevel("38")!!, 0.01f)
        assertEquals(12.0f, ChannelSettingsParser.parseDenonLevel("62")!!, 0.01f)
        assertEquals(4.5f, ChannelSettingsParser.parseDenonLevel("545")!!, 0.01f)
        assertEquals(0.5f, ChannelSettingsParser.parseDenonLevel("505")!!, 0.01f)
        assertEquals(-0.5f, ChannelSettingsParser.parseDenonLevel("495")!!, 0.01f)
        assertEquals(0.5f, ChannelSettingsParser.parseDenonLevel("50.5")!!, 0.01f)
        assertNull(ChannelSettingsParser.parseDenonLevel(null))
        assertNull(ChannelSettingsParser.parseDenonLevel(""))
    }

    @Test
    fun testFormatLevel() {
        assertEquals("0.0 dB", ChannelSettingsParser.formatLevel(0.0f))
        assertEquals("+3.5 dB", ChannelSettingsParser.formatLevel(3.5f))
        assertEquals("-2.0 dB", ChannelSettingsParser.formatLevel(-2.0f))
        assertEquals("--", ChannelSettingsParser.formatLevel(null))
    }

    @Test
    fun testParseDistance() {
        assertEquals(3.00f, ChannelSettingsParser.parseDistance("0300", "m")!!, 0.01f)
        assertEquals(3.60f, ChannelSettingsParser.parseDistance("0360", "m")!!, 0.01f)
        assertNull(ChannelSettingsParser.parseDistance("0000", "m"))
        assertNull(ChannelSettingsParser.parseDistance(null, "m"))
    }

    @Test
    fun testParseCrossover() {
        assertEquals("Full Band", ChannelSettingsParser.parseCrossover("FUL"))
        assertEquals("Full Band", ChannelSettingsParser.parseCrossover("FULL"))
        assertEquals("80 Hz", ChannelSettingsParser.parseCrossover("080"))
        assertEquals("120 Hz", ChannelSettingsParser.parseCrossover("120"))
        assertNull(ChannelSettingsParser.parseCrossover(null))
    }

    @Test
    fun testParseActiveChannelsFromJson() {
        val json = """
            {
              "ChSetup": [
                { "FL": "L" },
                { "C": "S" },
                { "FR": "L" },
                { "SLA": "S" },
                { "SRA": "S" },
                { "SBL": "N" },
                { "SBR": "N" },
                { "SW1": "Y" }
              ],
              "SWSetup": {
                "SWNum": 1
              }
            }
        """.trimIndent()

        val active = AvrBinaryProtocol.parseActiveChannelsFromJson(json)
        assertEquals(listOf("FL", "C", "FR", "SLA", "SRA", "SW1"), active)
    }

    @Test
    fun testParseActiveChannelsFromSspc() {
        val sspcLines = listOf(
            "SSSPCFRO YES",
            "SSSPCCEN YES",
            "SSSPCSUA YES",
            "SSSPCSBK NO",
            "SSSPCSWM STD",
            "SSSPCSWF 2SP",
        )

        val active = ChannelSettingsParser.parseActiveChannelsFromSspc(sspcLines)
        assertEquals(listOf("FL", "FR", "C", "SL", "SR", "SW1", "SW2"), active)
    }

    @Test
    fun testFilterToConnectedChannelsOnly() {
        // AVR returns dump with 30+ DSP channels, but user only has 5.1 connected
        val distanceLines = listOf(
            "SSSDEFL 0300", "SSSDEFR 0300", "SSSDEC 0300", "SSSDESL 0300", "SSSDESR 0300", "SSSDESW 0300",
            "SSSDESBL 0300", "SSSDESBR 0300", "SSSDEFHL 0360", "SSSDEFHR 0360", "SSSDESTP 01M"
        )
        val levelLines = listOf(
            "SSLEVFL 50", "SSLEVFR 50", "SSLEVC 50", "SSLEVSL 50", "SSLEVSR 50", "SSLEVSW 50",
            "SSLEVSBL 50", "SSLEVSBR 50", "SSLEVFHL 50", "SSLEVFHR 50", "SSLEVFWL 50", "SSLEVFWR 50"
        )
        val crossoverLines = listOf("SSCFRFRO FUL", "SSCFRCEN FUL", "SSCFRSUA FUL")
        val trimLines = listOf("CVFL 50", "CVFR 50", "CVC 50", "CVSL 50", "CVSR 50", "CVEND")

        val sspcLines = listOf(
            "SSSPCFRO YES",
            "SSSPCCEN YES",
            "SSSPCSUA YES",
            "SSSPCSBK NO",
            "SSSPCSWF 1SP"
        )

        val summary = ChannelSettingsParser.parseChannelSettings(
            distanceLines = distanceLines,
            crossoverLines = crossoverLines,
            levelLines = levelLines,
            trimLines = trimLines,
            sspcLines = sspcLines,
            lpfLfeLine = "SSLFL 120",
            subModeLine = "SSSWO LFE",
            port1256ActiveChannels = listOf("FL", "C", "FR", "SLA", "SRA", "SW1"),
        )

        // Must ONLY contain the 6 connected speakers (5.1)
        assertEquals(6, summary.channels.size)
        val ids = summary.channels.map { it.id }
        assertEquals(listOf("FL", "C", "FR", "SR", "SL", "SW1"), ids)
    }
}
