// Regression tests for the model naming and maneuver-resolution defaults.
// All names here are invented; no real cluster identifier is used.

package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDefaultsTest {

    @Test
    fun `an NTORQ name resolves to the rider's 125 not the 150`() {
        assertEquals(VehicleModel.NTORQ_125_RACE, VehicleModel.detect("NTORQ 125"))
        assertEquals(VehicleModel.NTORQ_125_RACE, VehicleModel.detect("ntorq"))
    }

    @Test
    fun `a name with no model still guesses UNKNOWN`() {
        // The cluster advertises no model in its name; the scan screen applies
        // the rider's 125 default separately. Detection alone must stay honest.
        assertEquals(VehicleModel.UNKNOWN, VehicleModel.detect("TVSBT800450"))
    }

    @Test
    fun `the exit pictogram rule is reachable`() {
        // The generic roundabout rule used to be first and shadowed this one.
        assertEquals(65, ManeuverResolver.fromText("Take the second exit at the roundabout"))
        assertEquals(65, ManeuverResolver.fromText("First exit"))
        assertEquals(68, ManeuverResolver.fromText("At the roundabout"))
    }
}
