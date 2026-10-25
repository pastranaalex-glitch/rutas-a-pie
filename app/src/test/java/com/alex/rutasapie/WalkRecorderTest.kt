package com.alex.rutasapie

import org.junit.Assert.*
import org.junit.Test

class WalkRecorderTest {
    private fun fix(ms:Long,lat:Double=40.0,accuracy:Double=5.0,speed:Double?=1.2)=GpsFix(lat,-3.0,accuracy,1_700_000_000_000+ms,ms,650.0,speed)
    @Test fun firstFixDoesNotInventDistance() {
        val r=WalkRecorder();r.resume(1000)
        assertNotNull(r.accept(fix(1000),1000));assertEquals(0.0,r.distanceMeters,0.0)
    }
    @Test fun walkingAccumulatesDistance() {
        val r=WalkRecorder();r.resume(1000);r.accept(fix(1000),1000)
        assertNotNull(r.accept(fix(11_000,40.0001),11_000));assertEquals(11.12,r.distanceMeters,0.1)
    }
    @Test fun stationaryNoiseIsIgnored() {
        val r=WalkRecorder();r.resume(1000);r.accept(fix(1000),1000)
        assertNull(r.accept(fix(5000,40.000005),5000));assertEquals(0.0,r.distanceMeters,0.0)
    }
    @Test fun lowDopplerSpeedSuppressesDrift() {
        val r=WalkRecorder();r.resume(1000);r.accept(fix(1000),1000)
        assertNull(r.accept(fix(11_000,40.0001,speed=0.1),11_000))
    }
    @Test fun pauseDoesNotBridgeLocationsOrCountTime() {
        val r=WalkRecorder();r.resume(1000);val a=r.accept(fix(1000),1000)!!
        r.pause(5000);assertEquals(4000,r.duration(50_000))
        r.resume(50_000);val b=r.accept(fix(50_000,41.0),50_000)!!
        assertTrue(b.point.segment>a.point.segment);assertEquals(0.0,r.distanceMeters,0.0);assertEquals(5000,r.duration(51_000))
    }
    @Test fun rejectsOldAndFutureLocations() {
        val r=WalkRecorder();r.resume(0)
        assertNull(r.accept(fix(1000),50_000));assertNull(r.accept(fix(60_000),50_000))
    }
    @Test fun rejectsDuplicateOrReorderedFixes() {
        val r=WalkRecorder();r.resume(0);r.accept(fix(5000),5000)
        assertNull(r.accept(fix(5000,40.0001),5000));assertNull(r.accept(fix(4000,40.0001),5000))
    }
    @Test fun rejectsPoorAccuracyAndInvalidCoordinates() {
        val r=WalkRecorder();r.resume(0)
        assertNull(r.accept(fix(1000,accuracy=100.0),1000));assertNull(r.accept(fix(2000,lat=Double.NaN),2000))
        assertNull(r.accept(fix(3000,accuracy=0.0),3000));assertNull(r.accept(fix(4000,lat=91.0),4000))
    }
    @Test fun repeatedImpossibleJumpsStayRejected() {
        val r=WalkRecorder();r.resume(0);r.accept(fix(1000),1000)
        assertNull(r.accept(fix(3000,41.0),3000));assertNull(r.accept(fix(6000,41.0001),6000));assertEquals(0.0,r.distanceMeters,0.0)
    }
    @Test fun signalGapStartsAnotherSegment() {
        val r=WalkRecorder();r.resume(0);val first=r.accept(fix(1000),1000)!!
        val after=r.accept(fix(100_000,41.0),100_000)!!
        assertTrue(after.point.segment>first.point.segment);assertEquals(0.0,r.distanceMeters,0.0)
    }
    @Test fun persistedTotalsResumeWithoutBootClock() {
        val r=WalkRecorder(1000.0,180_000,3);r.resume(1000)
        val p=r.accept(fix(1000),1000)!!
        assertEquals(3,p.point.segment);assertEquals(181_000,r.duration(2000));assertEquals(1000.0,r.distanceMeters,0.0)
    }
    @Test fun duplicatePauseOrResumeIsIdempotent() {
        val r=WalkRecorder();r.resume(1000);r.resume(2000);r.pause(3000);r.pause(5000)
        assertEquals(2000,r.duration(9000))
    }
    @Test fun canWalkWithoutSpeedField() {
        val r=WalkRecorder();r.resume(0);r.accept(fix(1000,speed=null),1000)
        assertNotNull(r.accept(fix(11_000,40.0001,speed=null),11_000))
    }
    @Test fun fastTransportNotCountedAsWalking() {
        val r=WalkRecorder();r.resume(0);assertNull(r.accept(fix(1000,speed=20.0),1000))
    }
    @Test fun averageUsesHoursCorrectly() = assertEquals(4.0,GeoMath.averageKmh(2000.0,1_800_000),0.0)
    @Test fun durationFormatsPastAnHour() = assertEquals("02:03:04",GeoMath.duration(7_384_000))
    @Test fun geodesicHandlesDateLine() = assertEquals(22239.0,GeoMath.distance(0.0,179.9,0.0,-179.9),20.0)
    @Test fun bearingNorth() = assertEquals(0.0,GeoMath.bearing(40.0,-3.0,40.1,-3.0),0.0)
}
