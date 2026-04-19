package borg.trikeshed.parse.json

import borg.trikeshed.common.readLines
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonParserBigJsonJvmTest {
    @Test
    fun testParseBigJson() {
        val jsonContent = readBigJson()
        val result = JsonParser.reify(jsonContent.toSeries())

        assertTrue(result is Map<*, *>, "Result should be a Map")

        val json = result as Map<String, Any?>

        assertEquals(5532807773L, json["id64"])
        assertEquals("Jackson's Lighthouse", json["name"])
        assertEquals("Federation", json["allegiance"])
        assertEquals("Confederacy", json["government"])
        assertEquals("Industrial", json["primaryEconomy"])
        assertEquals("Extraction", json["secondaryEconomy"])
        assertEquals("Low", json["security"])
        assertEquals(0, json["population"])
        assertEquals(7, json["bodyCount"])

        val coords = json["coords"] as Map<String, Any?>
        assertEquals(157.0, coords["x"])
        assertEquals(-27.0, coords["y"])
        assertEquals(-70.0, coords["z"])

        val bodies = json["bodies"] as List<*>
        assertEquals(7, bodies.size)

        val mainStar = bodies[0] as Map<String, Any?>
        assertEquals(5532807773L, mainStar["id64"])
        assertEquals(0, mainStar["bodyId"])
        assertEquals("Jackson's Lighthouse", mainStar["name"])
        assertEquals("Star", mainStar["type"])
        assertEquals("Neutron Star", mainStar["subType"])
        assertEquals(0.0, mainStar["distanceToArrival"])
        assertTrue(mainStar["mainStar"] as Boolean)
        assertEquals(266, mainStar["age"])
        assertEquals(null, mainStar["spectralClass"])
        assertEquals("VII", mainStar["luminosity"])
        assertEquals(-16.393875, mainStar["absoluteMagnitude"])
        assertEquals(13.484375, mainStar["solarMasses"])

        val stations = mainStar["stations"] as List<*>
        assertTrue(stations.isEmpty())

        val rawPlanet = bodies[1]
        assertTrue(rawPlanet is Map<*, *>, "bodies[1] should be a Map but was $rawPlanet")
        @Suppress("UNCHECKED_CAST")
        val planet = rawPlanet as Map<String, Any?>
        assertEquals("Jackson's Lighthouse 1", planet["name"])
        assertEquals("Planet", planet["type"])
        assertEquals("Class V gas giant", planet["subType"])
        assertEquals(962.175568, planet["distanceToArrival"])
        assertFalse(planet["isLandable"] as Boolean)

        val atmosphere: Map<String, *> = planet["atmosphereComposition"] as Map<String, *>
        assertEquals(28.05669, atmosphere["Helium"])
        assertEquals(71.943306, atmosphere["Hydrogen"])

        val planetStations: List<*> = planet["stations"] as List<*>
        assertEquals(2, planetStations.size)

        val firstStation = planetStations[0] as Map<String, Any?>
        assertEquals("T9Z-L7G", firstStation["name"])
        assertEquals(3708954368L, firstStation["id"])
        assertEquals("FleetCarrier", firstStation["controllingFaction"])

        val services = firstStation["services"] as List<*>
        assertTrue(services.contains("Dock"))
        assertTrue(services.contains("Market"))
        assertTrue(services.contains("Universal Cartographics"))
    }

    private fun readBigJson(): String = readLines("src/commonTest/resources/big.json").joinToString("\n")
}
