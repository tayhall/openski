package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class BenchMathTest {
    private fun sample(i: Int, t: Long, ax: Double, ay: Double, az: Double, gx: Double = 0.0, gy: Double = 0.0, gz: Double = 0.0) =
        SensorSample(i, t, ax.toFloat(), ay.toFloat(), az.toFloat(), gx.toFloat(), gy.toFloat(), gz.toFloat())

    private fun still(count: Int, az: Double = 9.80665, step: Long = 20) =
        (0 until count).map { sample(it, 1000 + it * step, 0.0, 0.0, az) }

    @Test fun summaryReportsMeanRateAndSteadiness() {
        val capture = BenchMath.summarize(still(150))!!
        assertEquals(9.80665, capture.magnitude, 1e-4)
        assertEquals(50.0, capture.effectiveHz!!, 0.5)
        assertEquals(0, capture.gaps)
        assertTrue(capture.steady)
        assertEquals(0.0, capture.gyroDps.norm(), 1e-9)
    }

    @Test fun gyroBiasIsReportedInDegreesPerSecond() {
        val bias = Math.toRadians(0.5)
        val capture = BenchMath.summarize((0 until 100).map { sample(it, it * 20L, 0.0, 0.0, 9.8, gx = bias) })!!
        assertEquals(0.5, capture.gyroDps.x, 1e-3)
        assertEquals(0.0, capture.gyroNoiseDps, 1e-3)
    }

    @Test fun movementAndShortCapturesAreNotSteady() {
        val moving = (0 until 100).map { sample(it, it * 20L, 0.0, 0.0, 9.8, gx = if (it % 2 == 0) 1.0 else -1.0) }
        assertFalse(BenchMath.summarize(moving)!!.steady)
        assertFalse(BenchMath.summarize(still(10))!!.steady)
        assertNull(BenchMath.summarize(still(1)))
    }

    @Test fun gapsAreCountedAndTimestampWrapIsSurvived() {
        val gappy = still(50).toMutableList()
        gappy[25] = gappy[25].copy(timestampMs = gappy[25].timestampMs + 300)
        assertTrue(BenchMath.summarize(gappy)!!.gaps >= 1)
        val wrap = listOf(sample(0, 4_294_967_280L, 0.0, 0.0, 9.8), sample(1, 4L, 0.0, 0.0, 9.8))
        assertEquals(20.0, BenchMath.summarize(wrap)!!.seconds * 1000, 1e-6)
    }

    @Test fun faceIsTheStrongestSignedAxis() {
        assertEquals(Face(2, 1), BenchMath.face(Vector3(0.3, -0.2, 9.8)))
        assertEquals(Face(0, -1), BenchMath.face(Vector3(-9.7, 0.5, 0.5)))
        assertEquals("−Y up", Face(1, -1).label)
        assertEquals(6, Face.all.size)
    }

    @Test fun angleBetweenMatchesAKnownTilt() {
        val flat = Vector3(0.0, 0.0, 9.80665)
        val tilted = Vector3(0.0, 9.80665 * sin(Math.toRadians(20.0)), 9.80665 * cos(Math.toRadians(20.0)))
        assertEquals(20.0, BenchMath.angleBetween(flat, tilted), 1e-6)
        assertEquals(0.0, BenchMath.angleBetween(flat, flat), 1e-6)
        assertEquals(0.0, BenchMath.angleBetween(Vector3(0.0, 0.0, 0.0), flat), 0.0)
        assertEquals(20.0, BenchMath.offFace(tilted, Face(2, 1)), 1e-6)
    }

    @Test fun axisChecksFindOffsetAndScaleFromOppositeFaces() {
        // X reads +9.9 and -9.7: offset +0.1, half-difference 9.8, scale -0.07%.
        val readings = mapOf(
            Face(0, 1) to Vector3(9.9, 0.0, 0.0), Face(0, -1) to Vector3(-9.7, 0.0, 0.0),
            Face(1, 1) to Vector3(0.0, 9.80665, 0.0), Face(1, -1) to Vector3(0.0, -9.80665, 0.0),
            Face(2, 1) to Vector3(0.0, 0.0, 10.3))
        val checks = BenchMath.axisChecks(readings)
        assertEquals(2, checks.size)
        assertEquals(0.1, checks[0].offset, 1e-9)
        assertEquals(-0.0679, checks[0].scalePercent, 1e-3)
        assertEquals(0.0, checks[1].offset, 1e-9)
        assertEquals(0.0, checks[1].scalePercent, 1e-9)
    }
}

class LandmarksTest {
    @Test fun standingOnTheHolesEdgePointsTheHolesDownSoTheDirectionIsOpposite() {
        // Holes edge down: the axis toward the holes reads -g, so the axis pointing up is the opposite one.
        assertEquals(Face(1, 1), BenchMath.holesDirection(Vector3(0.1, -9.8, 0.2)))
        assertEquals(Face(0, -1), BenchMath.holesDirection(Vector3(9.8, 0.0, 0.1)))
    }

    @Test fun landmarksNameTheirOwnAxesAndTheOpposites() {
        val marks = Landmarks(holes = Face(1, 1), chip = Face(2, 1))
        assertEquals("toward the holes edge", marks.name(Face(1, 1)))
        assertEquals("toward the header edge, away from the holes", marks.name(Face(1, -1)))
        assertEquals("out of the chip side", marks.name(Face(2, 1)))
        assertEquals("out of the back", marks.name(Face(2, -1)))
    }

    @Test fun theThirdAxisIsNamedFromRightHandedGeometry() {
        // holes +Y, chip +Z: holes x chip = +X, the right-hand edge with the chip facing you and the holes edge up.
        val marks = Landmarks(holes = Face(1, 1), chip = Face(2, 1))
        assertTrue(marks.name(Face(0, 1))!!.contains("right-hand"))
        assertTrue(marks.name(Face(0, -1))!!.contains("left-hand"))
        // Swapping the chip side flips the handedness.
        val flipped = Landmarks(holes = Face(1, 1), chip = Face(2, -1))
        assertTrue(flipped.name(Face(0, 1))!!.contains("left-hand"))
    }

    @Test fun unknownLandmarksFallBackToThePlainAxisLabel() {
        assertNull(Landmarks(null, null).name(Face(0, 1)))
        assertNull(Landmarks(Face(1, 1), null).name(Face(0, 1)))
        assertEquals("+X toward the toe", Mounting(0, 1).label(Landmarks(null, null)))
        assertEquals("+Y: toward the holes edge", Mounting(1, 1).label(Landmarks(Face(1, 1), null)))
    }
}
