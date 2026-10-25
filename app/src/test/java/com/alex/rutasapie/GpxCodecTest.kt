package com.alex.rutasapie

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class GpxCodecTest {
    private fun read(text:String)=GpxCodec.read(text.byteInputStream())
    private fun gpx(inner:String)="<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\">$inner</gpx>"
    @Test fun roundTripPreservesCoordinatesTimeElevationAndSegments() {
        val points=listOf(TrackPoint(40.0,-3.0,1_700_000_000_000,5.0,650.0,0),TrackPoint(40.001,-3.0,1_700_000_010_000,5.0,null,0),TrackPoint(40.5,-3.0,1_700_000_100_000,5.0,651.0,1))
        val out=ByteArrayOutputStream();GpxCodec.write(out,"Peña & <Sol>",points)
        val parsed=GpxCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("Peña & <Sol>",parsed.name);assertEquals(3,parsed.points.size)
        assertEquals(points.map{it.copy(accuracy=0.0)},parsed.points)
    }
    @Test fun routePointsSupportedWithoutDates() {
        val r=read(gpx("<rte><name>Paseo</name><rtept lat=\"40\" lon=\"-3\"/><rtept lat=\"40.1\" lon=\"-3\"/></rte>"))
        assertEquals(2,r.points.size);assertEquals(0L,r.points[0].timeMillis)
    }
    @Test fun bomSupported() = assertEquals(1,read("\uFEFF"+gpx("<trk><trkseg><trkpt lat=\"40\" lon=\"-3\"/></trkseg></trk>")).points.size)
    @Test(expected=IllegalArgumentException::class) fun rejectsOutOfRangeCoordinates() {read(gpx("<trk><trkseg><trkpt lat=\"100\" lon=\"-3\"/></trkseg></trk>"))}
    @Test(expected=IllegalArgumentException::class) fun rejectsNan() {read(gpx("<trkpt lat=\"NaN\" lon=\"-3\"/>"))}
    @Test(expected=IllegalArgumentException::class) fun rejectsDtdAndEntities() {read("<!DOCTYPE gpx [<!ENTITY x SYSTEM \"file:///private\">]>"+gpx("<name>&x;</name>"))}
    @Test(expected=IllegalArgumentException::class) fun rejectsNoTrack() {read(gpx("<wpt lat=\"40\" lon=\"-3\"/>"))}
    @Test(expected=Exception::class) fun rejectsTruncatedXml() {read("<gpx><trk><trkseg><trkpt lat=\"40\" lon=\"-3\">")}
    @Test(expected=IllegalArgumentException::class) fun rejectsNonFiniteAltitude() {read(gpx("<trkpt lat=\"40\" lon=\"-3\"><ele>Infinity</ele></trkpt>"))}
    @Test(expected=IllegalArgumentException::class) fun rejectsBadTimestamp() {read(gpx("<trkpt lat=\"40\" lon=\"-3\"><time>yesterday</time></trkpt>"))}
    @Test(expected=IllegalArgumentException::class) fun limitsInputBytes() {GpxCodec.read(ByteArrayInputStream(ByteArray(GpxCodec.MAX_BYTES+1)))}
    @Test(expected=Exception::class) fun rejectsInvalidUtf8() {GpxCodec.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(),0x28)))}
    @Test fun routeAndTrackSegmentsRemainSeparate() {
        val p=read(gpx("<rte><rtept lat=\"40\" lon=\"-3\"/></rte><trk><trkseg><trkpt lat=\"41\" lon=\"-3\"/></trkseg></trk>")).points
        assertNotEquals(p[0].segment,p[1].segment)
    }
}
