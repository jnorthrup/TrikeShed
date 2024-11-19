package borg.trikeshed.parse.json

import borg.trikeshed.common.readLines
import borg.trikeshed.lib.*
import kotlin.test.*

class JsonParserTest {

    @Test
    fun `test parse big json`() {
        // Read the big.json file content
        val jsonContent = readBigJson()

        // Parse the JSON
        val result = JsonParser.reify(jsonContent.toSeries())

        // Verify it's a map
        assertTrue(result is Map<*, *>, "Result should be a Map")

        val json = result as Map<String, Any?>

        // Test top level properties
        assertEquals(5532807773L, json["id64"])
        assertEquals("Jackson's Lighthouse", json["name"])
        assertEquals("Federation", json["allegiance"])
        assertEquals("Confederacy", json["government"])
        assertEquals("Industrial", json["primaryEconomy"])
        assertEquals("Extraction", json["secondaryEconomy"])
        assertEquals("Low", json["security"])
        assertEquals(0, json["population"])
        assertEquals(7, json["bodyCount"])

        // Test nested coords object
        val coords = json["coords"] as Map<String, Any?>
        assertEquals(157.0, coords["x"])
        assertEquals(-27.0, coords["y"])
        assertEquals(-70.0, coords["z"])

        // Test bodies array
        val bodies = json["bodies"] as List<*>
        assertEquals(7, bodies.size)

        // Test first body (the star)
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

        // Test stations array in first body
        val stations = mainStar["stations"] as List<*>
        assertTrue(stations.isEmpty())

        // Test a planet (second body)
        val planet = bodies[1] as Map<String, Any?>
        assertEquals("Jackson's Lighthouse 1", planet["name"])
        assertEquals("Planet", planet["type"])
        assertEquals("Class V gas giant", planet["subType"])
        assertEquals(962.175568, planet["distanceToArrival"])
        assertFalse(planet["isLandable"] as Boolean)

        // Test nested atmosphereComposition in planet
        val atmosphere = planet["atmosphereComposition"] as Map<String, Any?>
        assertEquals(28.05669, atmosphere["Helium"])
        assertEquals(71.943306, atmosphere["Hydrogen"])

        // Test stations in planet
        val planetStations = planet["stations"] as List<*>
        assertEquals(2, planetStations.size)

        val firstStation = planetStations[0] as Map<String, Any?>
        assertEquals("T9Z-L7G", firstStation["name"])
        assertEquals(3708954368L, firstStation["id"])
        assertEquals("FleetCarrier", firstStation["controllingFaction"])

        // Test services array in station
        val services = firstStation["services"] as List<*>
        assertTrue(services.contains("Dock"))
        assertTrue(services.contains("Market"))
        assertTrue(services.contains("Universal Cartographics"))
    }


    fun readBigJson(): String {
        val lines = readLines("src/main/resources/big.json")
        return lines.joinToString("\n")
    }
}