package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OverpassParserTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/overpass/$name")!!.bufferedReader().readText()

    private fun parsed(text: String): ResortData = when (val r = OverpassParser.parseResort(text)) {
        is OverpassParse.Parsed -> r.value
        is OverpassParse.Failed -> { fail("Parse failed: ${r.message}"); error("unreachable") }
    }

    private fun failed(text: String): String = when (val r = OverpassParser.parseResort(text)) {
        is OverpassParse.Failed -> r.message
        is OverpassParse.Parsed -> { fail("Expected a failure, got $r"); error("unreachable") }
    }

    @Test fun normalResponse() {
        val data = parsed(fixture("resort_normal.json"))
        assertEquals("2026-10-19T08:15:42Z", data.osmBase)
        assertNull(data.remark)
        assertFalse(data.partial)
        assertEquals(listOf(100001L, 100002L, 100003L, 100003L, 100005L, 100006L), data.ways.map { it.way.osmId })
        assertEquals(2, data.skipped) // the one-point way and the untagged way

        val lift = data.ways[0]
        assertEquals(WayKind.LIFT, lift.way.kind)
        assertEquals("Sunny Ridge", lift.way.name)
        assertEquals("C4", lift.way.ref)
        assertEquals("chair_lift", lift.way.aerialway)
        assertNull(lift.way.difficulty)
        assertEquals("Example Lifts", lift.operator)
        assertEquals(7, lift.version)
        assertEquals(listOf(LatLon(46.0101, 7.0102), LatLon(46.0201, 7.0202)), lift.way.points)

        val blue = data.ways[1]
        assertEquals(WayKind.PISTE, blue.way.kind)
        assertEquals("Blue Fox", blue.way.name)
        assertEquals(PisteDifficulty.EASY, blue.way.difficulty)
        assertEquals("Novice", blue.difficultyRaw)
        assertNull(blue.way.aerialway)
        assertFalse(blue.area)
        assertEquals(3, blue.way.points.size)

        // A null geometry point splits the way into two parts instead of bridging the gap.
        val (before, after) = data.ways[2] to data.ways[3]
        assertEquals(listOf(0, 1), listOf(before.part, after.part))
        assertEquals(listOf(LatLon(46.019, 7.019), LatLon(46.018, 7.0185)), before.way.points)
        assertEquals(listOf(LatLon(46.013, 7.0125), LatLon(46.012, 7.012)), after.way.points)
        for (unnamed in listOf(before, after)) {
            assertNull(unnamed.way.name)
            assertEquals(PisteDifficulty.OTHER, unnamed.way.difficulty)
            assertEquals("very hard", unnamed.difficultyRaw)
        }

        val tBar = data.ways[4]
        assertEquals("t-bar", tBar.way.aerialway)
        assertNull("piste:name is not a lift name", tBar.way.name)

        val oldRoad = data.ways[5]
        assertEquals("Old Road", oldRoad.way.name)
        assertEquals("12", oldRoad.way.ref)
        assertEquals(PisteDifficulty.INTERMEDIATE, oldRoad.way.difficulty)

        assertEquals(listOf(900001L, 900002L, 100008L), data.stations.map { it.osmId })
        assertEquals(listOf("node", "node", "way"), data.stations.map { it.osmType })
        assertEquals("Valley station", data.stations[0].name)
        assertEquals(LatLon(46.0101, 7.0102), data.stations[0].point)
        assertEquals(4, data.stations[0].version)
        assertNull(data.stations[1].name)
        // A station mapped as a closed outline becomes a point at the mean of its four corners.
        val outline = data.stations[2]
        assertEquals("Ridge top station", outline.name)
        assertEquals(46.0201, outline.point.lat, 1e-9)
        assertEquals(7.0202, outline.point.lon, 1e-9)
        assertEquals(2, outline.version)
        assertTrue("a station outline is not a lift", data.ways.none { it.way.osmId == 100008L })
    }

    @Test fun emptyResponse() {
        val data = parsed(fixture("resort_empty.json"))
        assertTrue(data.isEmpty)
        assertFalse(data.partial)
        assertEquals(0, data.skipped)
    }

    @Test fun partialResponseKeepsTheRemark() {
        val data = parsed(fixture("resort_partial.json"))
        assertTrue(data.partial)
        assertTrue(data.remark!!.startsWith("runtime error: Query timed out"))
        assertEquals(1, data.ways.size)
    }

    @Test fun areasAndRelations() {
        val data = parsed(fixture("resort_area.json"))
        val byId = data.ways.associateBy { it.way.osmId }
        assertEquals("the inner ring is left out", setOf(200001L, 200002L, 200010L, 200012L), byId.keys)

        val meadow = byId.getValue(200001)
        assertTrue("closed area=yes outline is marked", meadow.area)
        assertEquals(4, meadow.way.points.size)
        assertEquals(meadow.way.points.first(), meadow.way.points.last())
        assertEquals(PisteDifficulty.EASY, meadow.way.difficulty)

        val valley = byId.getValue(200002)
        assertEquals("route relation fills the unnamed way", "Long Valley Run", valley.way.name)
        assertEquals("7", valley.way.ref)
        assertEquals(PisteDifficulty.ADVANCED, valley.way.difficulty)
        assertEquals("advanced", valley.difficultyRaw)
        assertEquals(300002L, valley.relationId)
        assertEquals("the way keeps its own version", 6, valley.version)
        assertFalse(valley.area)

        for (id in listOf(200010L, 200012L)) {
            val member = byId.getValue(id)
            assertEquals("Big Bowl", member.way.name)
            assertEquals(PisteDifficulty.FREERIDE, member.way.difficulty)
            assertEquals(300001L, member.relationId)
            assertNull(member.version)
        }
        assertTrue("closed outer ring is an area", byId.getValue(200010).area)
        assertEquals(5, byId.getValue(200010).way.points.size)
        assertFalse("an open outer segment is not marked as an area", byId.getValue(200012).area)
        assertEquals(0, data.skipped)
    }

    @Test fun closedPisteWithoutAreaTagIsAnArea() {
        val text = """{"elements":[{"type":"way","id":5,"geometry":[{"lat":1,"lon":1},{"lat":1,"lon":2},{"lat":2,"lon":2},{"lat":1,"lon":1}],
            "tags":{"piste:type":"downhill"}},{"type":"way","id":6,"geometry":[{"lat":1,"lon":1},{"lat":1,"lon":2},{"lat":2,"lon":2},{"lat":1,"lon":1}],
            "tags":{"piste:type":"downhill","area":"no"}}]}"""
        val data = parsed(text)
        assertTrue(data.ways[0].area)
        assertFalse(data.ways[1].area)
    }

    @Test fun malformedInputFailsCleanly() {
        val truncated = fixture("resort_normal.json").take(1500)
        assertTrue(failed(truncated).startsWith("Unreadable response"))
        assertEquals("Empty response", failed(""))
        assertTrue(failed("<html><body>rate_limited</body></html>").contains("not a JSON object"))
        assertTrue(failed("[1,2,3]").contains("not a JSON object"))
        assertEquals("No elements array in the response", failed("{\"version\":0.6}"))
        assertTrue(failed("{\"elements\":[").startsWith("Unreadable response"))
        assertTrue(failed("[".repeat(100_000)).startsWith("Unreadable response"))
    }

    @Test fun oddElementsAreSkippedNotFatal() {
        val text = """{"elements":[1,"x",{"type":"way"},{"type":"way","id":"abc","geometry":[]},
            {"type":"way","id":7,"geometry":[{"lat":"bad","lon":1},{"lat":95,"lon":1},{"lat":1,"lon":1},{"lat":1.5,"lon":1}],"tags":{"piste:type":"downhill","name":5}},
            {"type":"node","id":8,"tags":{"aerialway":"station"}},{"type":"area","id":9}]}"""
        val data = parsed(text)
        assertEquals(listOf(7L), data.ways.map { it.way.osmId })
        assertEquals(2, data.ways[0].way.points.size)
        assertNull("non-string tag ignored", data.ways[0].way.name)
        assertTrue(data.stations.isEmpty())
        assertEquals(6, data.skipped)
    }

    @Test fun difficultyNormalisation() {
        assertEquals(PisteDifficulty.EASY, PisteDifficulty.normalise("novice"))
        assertEquals(PisteDifficulty.EXPERT, PisteDifficulty.normalise(" Extreme"))
        assertEquals(PisteDifficulty.OTHER, PisteDifficulty.normalise("black"))
        assertNull(PisteDifficulty.normalise("  "))
        assertNull(PisteDifficulty.normalise(null))
    }

    @Test fun searchResults() {
        val r = (OverpassParser.parseSearch(fixture("search.json")) as OverpassParse.Parsed).value
        assertEquals("unnamed elements still count as returned", 4, r.elementCount)
        assertEquals(listOf("node/400001", "way/400002", "relation/400003"), r.candidates.map { it.key })
        val node = r.candidates[0]
        assertNull(node.box)
        assertEquals(LatLon(46.1, 7.1), node.centre)
        val way = r.candidates[1]
        assertEquals("Example Valley Ski Area", way.name)
        assertEquals(46.02, way.centre.lat, 1e-9)
        assertEquals(7.03, way.centre.lon, 1e-9)
        assertEquals(GeoBox(46.0, 7.0, 46.04, 7.06), way.box)
    }

    @Test fun searchFailures() {
        assertTrue(OverpassParser.parseSearch("{\"elements\":[{") is OverpassParse.Failed)
        assertTrue(OverpassParser.parseSearch("""{"elements":[],"remark":"runtime error: out of memory"}""") is OverpassParse.Failed)
        assertEquals(OverpassParse.Parsed(ResortSearch(emptyList(), 0)), OverpassParser.parseSearch(fixture("resort_empty.json")))
    }
}
