package com.mapsdroid

import com.mapsdroid.core.ManeuverType
import com.mapsdroid.directions.ManeuverParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverParserTest {

    @Test
    fun textClassifiesTurns() {
        assertEquals(ManeuverType.UTURN, ManeuverParser.parse("Make a U-turn", null, null, false, false))
        assertEquals(ManeuverType.MERGE, ManeuverParser.parse("Merge onto US-101", null, null, false, false))
        assertEquals(ManeuverType.KEEP_RIGHT, ManeuverParser.parse("Keep right at the fork", null, null, false, false))
    }

    @Test
    fun firstAndLastStepsAreDepartAndArrive() {
        assertEquals(ManeuverType.DEPART, ManeuverParser.parse("Head north", null, null, true, false))
        assertEquals(ManeuverType.ARRIVE, ManeuverParser.parse("Arrive at destination", null, null, false, true))
    }

    @Test
    fun geometryRefinesSlightVsSharp() {
        // Incoming heading 0 (north), outgoing 10 (barely right) -> slight right.
        assertEquals(ManeuverType.SLIGHT_RIGHT, ManeuverParser.parse("Turn right", 0.0, 10.0, false, false))
        // Outgoing 150 (sharp right).
        assertEquals(ManeuverType.SHARP_RIGHT, ManeuverParser.parse("Turn right", 0.0, 150.0, false, false))
    }

    @Test
    fun extractsRoadName() {
        assertEquals("New Montgomery St", ManeuverParser.roadName("Turn right onto New Montgomery St"))
    }
}
