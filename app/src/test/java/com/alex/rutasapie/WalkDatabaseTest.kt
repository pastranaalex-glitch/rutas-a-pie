package com.alex.rutasapie

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WalkDatabaseTest {
    private val db get()=WalkDatabase.get(RuntimeEnvironment.getApplication())
    private fun p(lat:Double=40.0,time:Long=1_700_000_000_000,segment:Int=0)=TrackPoint(lat,-3.0,time,5.0,650.0,segment)
    @Before fun clear() {db.sessions().forEach {db.delete(it.id)}}
    @Test fun savesMetricsTogetherWithPoint() {
        val id=db.create("Mi paseo",1000);db.update(id,"recording",12.0,10_000,p())
        val s=db.session(id)!!;assertEquals(1,s.pointCount);assertEquals(12.0,s.distanceMeters,0.0);assertEquals(10_000,s.durationMillis)
        assertEquals(listOf(p()),db.points(id))
    }
    @Test fun recoveringProcessNeverLeavesRecordingRunning() {
        val id=db.create("Interrumpida",1000);db.update(id,"recording",12.0,10_000,p());db.recoverInterrupted()
        assertEquals("paused",db.session(id)?.status);assertEquals(10_000L,db.session(id)?.durationMillis)
    }
    @Test fun deletingWalkCascadesOnlyItsPoints() {
        val a=db.create("A",1000);val b=db.create("B",2000)
        db.update(a,"finished",0.0,0,p());db.update(b,"finished",0.0,0,p(41.0));db.delete(a)
        assertNull(db.session(a));assertTrue(db.points(a).isEmpty());assertEquals(1,db.points(b).size)
    }
    @Test fun gpxImportDoesNotConnectPauseSegments() {
        val id=db.importTrack("GPX",listOf(p(),p(40.0001,1_700_000_010_000),p(41.0,1_700_001_000_000,1)))
        val s=db.session(id)!!;assertEquals("imported",s.status);assertEquals(11.12,s.distanceMeters,0.1);assertEquals(10_000,s.durationMillis)
    }
    @Test fun exportedTrackRemainsAfterDatabaseReopen() {
        val id=db.create("Persistente",1000);db.update(id,"finished",0.0,0,p());db.close()
        assertEquals(listOf(p()),db.points(id));assertEquals("finished",db.session(id)?.status)
    }
    @Test fun missingWalkDoesNotCommitOrphanPoint() {
        try {db.update(9999,"recording",10.0,1000,p());fail("Debe rechazar") } catch(_:Exception) {}
        assertTrue(db.points(9999).isEmpty())
    }
    @Test fun preferencesUntouchedByWalkDeletion() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("rutas_a_pie_prefs",0)
        prefs.edit().putStringSet("favoritos_rutas",setOf("1")).commit()
        val id=db.create("Prueba",1000);db.delete(id)
        assertEquals(setOf("1"),prefs.getStringSet("favoritos_rutas",null))
    }
}
