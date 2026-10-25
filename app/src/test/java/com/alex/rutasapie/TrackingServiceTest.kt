package com.alex.rutasapie

import android.Manifest
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.SystemClock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class TrackingServiceTest {
    private lateinit var controller:ServiceController<TrackingService>
    private lateinit var service:TrackingService
    private lateinit var worker:Handler
    private val app get()=RuntimeEnvironment.getApplication()
    private val db get()=WalkDatabase.get(app)
    @Before fun setup() {
        db.sessions().forEach {db.delete(it.id)}
        TrackingRuntime.active=false;TrackingRuntime.publish(TrackingState())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager=app.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(true);shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER,true)
        controller=Robolectric.buildService(TrackingService::class.java).create();service=controller.get()
        val f=TrackingService::class.java.getDeclaredField("worker");f.isAccessible=true;worker=f.get(service) as Handler
    }
    private fun flush() {
        val latch=CountDownLatch(1);assertTrue(worker.post {latch.countDown()})
        assertTrue("El hilo GPS no respondió",latch.await(5,TimeUnit.SECONDS))
    }
    private fun command(action:String,id:Long=0) {
        service.onStartCommand(Intent(app,TrackingService::class.java).setAction(action).putExtra(TrackingService.EXTRA_ID,id).putExtra(TrackingService.EXTRA_NAME,"Prueba"),0,1)
        flush()
    }
    @After fun teardown() {
        val field=TrackingService::class.java.getDeclaredField("thread");field.isAccessible=true
        val thread=field.get(service) as android.os.HandlerThread
        controller.destroy();thread.join(5000);assertFalse("El hilo GPS debe cerrarse",thread.isAlive)
    }
    @Test fun startPauseResumeAndFinishPersistSession() {
        command(TrackingService.START);val id=TrackingRuntime.state.value.session!!.id
        assertEquals("recording",db.session(id)?.status)
        command(TrackingService.PAUSE);assertEquals("paused",db.session(id)?.status)
        command(TrackingService.RESUME);assertEquals("recording",db.session(id)?.status)
        command(TrackingService.FINISH);assertEquals("finished",db.session(id)?.status)
    }
    @Test fun locationCallbackCommitsPoint() {
        command(TrackingService.START)
        val fix=Location(LocationManager.GPS_PROVIDER).apply {
            latitude=40.0;longitude=-3.0;accuracy=5f;time=1_700_000_000_000;elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos();speed=1.0f
        }
        worker.post {service.onLocationChanged(fix)};flush()
        val id=TrackingRuntime.state.value.session!!.id
        assertEquals(1,db.session(id)?.pointCount);assertEquals(1,db.points(id).size)
    }
    @Test fun pauseRemovesLocationRequests() {
        command(TrackingService.START);command(TrackingService.PAUSE)
        val manager=service.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        assertTrue(shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
    }
    @Test fun cannotResumeFinishedSession() {
        val id=db.create("Finalizada",1000);db.update(id,"finished",0.0,0)
        command(TrackingService.RESUME,id)
        assertEquals("finished",db.session(id)?.status)
        assertTrue(TrackingRuntime.state.value.message.startsWith("Grabación detenida"))
    }
    @Test fun unavailableLocationDoesNotInventRecording() {
        val manager=service.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(false)
        command(TrackingService.START)
        assertEquals("paused",db.sessions().single().status)
    }
    @Test fun lateNotificationActionCannotReopenFinishedWalk() {
        command(TrackingService.START);val id=TrackingRuntime.state.value.session!!.id
        command(TrackingService.FINISH);command(TrackingService.PAUSE);command(TrackingService.RESUME)
        assertEquals("finished",db.session(id)?.status)
    }
}
