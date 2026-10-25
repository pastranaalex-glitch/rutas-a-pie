package com.alex.rutasapie

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackingState(
    val session: WalkSession?=null, val fix: GpsFix?=null,
    val message: String="GPS listo para iniciar", val live: Boolean=false
)

object TrackingRuntime {
    private val mutable=MutableStateFlow(TrackingState())
    val state=mutable.asStateFlow()
    @Volatile var active=false
        internal set
    internal fun publish(state: TrackingState) { mutable.value=state }
}

/** Started by an explicit user action in a visible Activity. No background-location permission. */
class TrackingService : Service(), LocationListener {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var manager: LocationManager
    private val db by lazy { WalkDatabase.get(this) }
    private var session: WalkSession?=null
    private var recorder: WalkRecorder?=null
    private var latestFix: GpsFix?=null
    private var message="Esperando una posición precisa…"
    private var listening=false
    private var lastCheckpoint=0L
    private var lastNotification=0L
    @Volatile private var shutdown=false

    override fun onCreate() {
        super.onCreate()
        thread=HandlerThread("RutasAPie-GPS").apply { start() }; worker=Handler(thread.looper)
        manager=getSystemService(LOCATION_SERVICE) as LocationManager
        if(Build.VERSION.SDK_INT>=26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL,"Grabación de caminatas",NotificationManager.IMPORTANCE_LOW).apply {
                    description="Control visible del GPS de Rutas a Pie"; setShowBadge(false)
                })
        }
        TrackingRuntime.active=true
        TrackingRuntime.publish(TrackingState(message="Iniciando GPS…",live=true))
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(shutdown) return START_NOT_STICKY
        val action=intent?.action ?: FINISH
        if(!hasLocationPermission(this)) {
            failOnMain("Permiso de ubicación retirado. La caminata queda guardada."); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this,NOTIFICATION_ID,notification(),if(Build.VERSION.SDK_INT>=29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: RuntimeException) {
            failOnMain("Android no permitió iniciar el GPS. Abre la app y revisa los permisos."); return START_NOT_STICKY
        }
        val id=intent?.getLongExtra(EXTRA_ID,0L) ?: 0L
        val name=intent?.getStringExtra(EXTRA_NAME) ?: "Mi caminata"
        worker.post { guarded {
            if(shutdown) return@guarded
            when(action) {
                START,RESUME -> startOrResume(id,name)
                PAUSE -> pause()
                FINISH -> finish()
            }
        } }
        return START_NOT_STICKY // A killed process recovers as paused, never silently restarts tracking.
    }
    private fun startOrResume(id: Long, name: String) {
        if(session==null) {
            db.recoverInterrupted()
            val selected=if(id>0) db.session(id) else null
            if(id>0) require(selected!=null && selected.status=="paused") { "Esta caminata no se puede reanudar." }
            val sessionId=selected?.id ?: db.create(name,System.currentTimeMillis())
            session=db.session(sessionId) ?: error("No se pudo crear la caminata.")
            val s=session!!
            recorder=WalkRecorder(s.distanceMeters,s.durationMillis,(db.points(s.id).lastOrNull()?.segment ?: -1)+1)
        } else if(id>0) require(id==session!!.id) { "Ya hay otra caminata abierta. Finalízala primero." }
        if(recorder!!.recording) { publish(); return }
        if(!manager.isLocationEnabledCompat()) { message="Activa la ubicación del teléfono y pulsa Reanudar."; publish(); return }
        recorder!!.resume(SystemClock.elapsedRealtime())
        session=session!!.copy(status="recording")
        message="Esperando señal GPS…"; latestFix=null
        checkpoint(); startUpdates(); publish()
        worker.removeCallbacks(tick); worker.post(tick)
    }
    @Suppress("MissingPermission")
    private fun startUpdates() {
        if(listening || !hasLocationPermission(this)) return
        var providers=0
        for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)) {
            if(provider==LocationManager.GPS_PROVIDER && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) continue
            if(manager.allProviders.contains(provider) && manager.isProviderEnabled(provider)) {
                try {
                    manager.requestLocationUpdates(provider,3_000L,0f,this,thread.looper); providers++
                } catch (_: IllegalArgumentException) { /* Optional provider not available on this device. */ }
            }
        }
        listening=providers>0
        if(!listening) { pause(); message="No hay proveedor de ubicación disponible. Revisa Ajustes."; publish() }
    }
    private fun stopUpdates() {
        if(listening) {
            try {manager.removeUpdates(this)} catch(_:SecurityException) { /* Permission may be revoked mid-session. */ }
            finally {listening=false}
        }
    }
    private fun pause() {
        recorder?.pause(SystemClock.elapsedRealtime()); stopUpdates(); latestFix=null
        session=session?.copy(status="paused"); message="Caminata pausada · el tiempo no avanza"
        checkpoint(); publish(); worker.removeCallbacks(tick)
        notifyUpdate()
    }
    private fun finish() {
        recorder?.pause(SystemClock.elapsedRealtime()); stopUpdates()
        session=session?.copy(status="finished"); message="Caminata guardada"; latestFix=null
        checkpoint(); publish(); shutdown=true; worker.removeCallbacks(tick)
        Handler(Looper.getMainLooper()).post { ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onLocationChanged(location: Location) = guarded {
        if(recorder?.recording!=true) return@guarded
        val fix=location.toFix()
        if(!fix.isValid() || SystemClock.elapsedRealtime()-fix.elapsedMillis !in -1000L..15_000L) return@guarded
        // A lower-quality network fix must not displace a fresh satellite fix in the UI.
        val old=latestFix
        if(old==null || fix.accuracy<=old.accuracy || fix.elapsedMillis-old.elapsedMillis>10_000L) latestFix=fix
        val accepted=recorder!!.accept(fix,SystemClock.elapsedRealtime())
        if(accepted!=null) {
            val s=session!!; val r=recorder!!
            db.update(s.id,"recording",r.distanceMeters,r.duration(SystemClock.elapsedRealtime()),accepted.point)
            session=s.copy(distanceMeters=r.distanceMeters,pointCount=s.pointCount+1)
            message="Grabando recorrido"; lastCheckpoint=SystemClock.elapsedRealtime()
        } else message=if(fix.accuracy>WalkRecorder.MAX_ACCURACY) "Señal imprecisa: esperando precisión de 35 m o mejor" else "Grabando · filtrando puntos repetidos y ruido GPS"
        publish()
    }
    override fun onProviderEnabled(provider: String) = guarded { if(recorder?.recording==true) { stopUpdates(); startUpdates() } }
    override fun onProviderDisabled(provider: String) = guarded {
        message="Señal GPS desactivada · activa Ubicación en Ajustes"; latestFix=null; publish()
    }
    @Deprecated("Required for older LocationListener implementations")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private val tick=object : Runnable {
        override fun run() = guarded {
            if(shutdown || recorder?.recording!=true) return@guarded
            val now=SystemClock.elapsedRealtime()
            if(ContextCompat.checkSelfPermission(this@TrackingService,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
                pause(); message="Ubicación precisa retirada. Actívala en Ajustes para reanudar."; publish(); return@guarded
            }
            if(now-lastCheckpoint>=15_000) checkpoint()
            if(latestFix?.let {now-it.elapsedMillis>15_000}==true) message="Sin señal reciente · esperando GPS"
            publish()
            if(now-lastNotification>=5_000) notifyUpdate()
            worker.postDelayed(this,1_000)
        }
    }
    private fun checkpoint() {
        val s=session ?: return; val r=recorder ?: return
        val now=SystemClock.elapsedRealtime()
        session=s.copy(distanceMeters=r.distanceMeters,durationMillis=r.duration(now))
        db.update(s.id,session!!.status,r.distanceMeters,r.duration(now)); lastCheckpoint=now
    }
    private fun publish() {
        val s=session?.let {it.copy(durationMillis=recorder?.duration(SystemClock.elapsedRealtime()) ?: it.durationMillis)}
        TrackingRuntime.publish(TrackingState(s,latestFix,message,true))
    }
    private fun notification(): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,GpsActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snapshot=TrackingRuntime.state.value.session
        val paused=snapshot?.status=="paused"
        fun control(action: String, code: Int) = PendingIntent.getService(this,code,Intent(this,TrackingService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val s=snapshot
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_walk_notification)
            .setContentTitle(s?.name ?: "Rutas a Pie · GPS")
            .setContentText(if(s==null) "Iniciando GPS…" else String.format(java.util.Locale.forLanguageTag("es-ES"),"%s · %.2f km · %s",if(paused) "Pausada" else "Grabando",s.distanceMeters/1000,GeoMath.duration(s.durationMillis)))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0,if(paused) "Reanudar" else "Pausar",control(if(paused) RESUME else PAUSE,1))
            .addAction(0,"Guardar y terminar",control(FINISH,2)).build()
    }
    private fun notifyUpdate() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID,notification())
        lastNotification=SystemClock.elapsedRealtime()
    }
    private fun guarded(block: ()->Unit) {
        try { block() } catch (e: Exception) {
            stopUpdates(); recorder?.pause(SystemClock.elapsedRealtime())
            session=session?.copy(status="paused")
            try { checkpoint() } catch (_: Exception) { /* Keep all previously committed points. */ }
            message="Grabación detenida: ${e.message ?: "no se pudo guardar"}. Revisa el almacenamiento y los permisos."
            publish(); shutdown=true; worker.removeCallbacks(tick)
            Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }
    private fun failOnMain(text: String) {
        message=text
        worker.post { guarded { recorder?.pause(SystemClock.elapsedRealtime()); session=session?.copy(status="paused"); checkpoint(); publish(); shutdown=true; stopSelf() } }
    }
    override fun onDestroy() {
        // Queue final persistence on the same worker to preserve the order of point writes.
        worker.post {
            try { stopUpdates(); if(!shutdown) { recorder?.pause(SystemClock.elapsedRealtime()); session=session?.copy(status="paused"); checkpoint() } }
            catch (_: Exception) { message="GPS cerrado. Se conservan los puntos guardados." }
            finally {
                TrackingRuntime.active=false
                TrackingRuntime.publish(TrackingState(session,latestFix,message,false))
                thread.quitSafely()
            }
        }
        super.onDestroy()
    }
    companion object {
        const val START="com.alex.rutasapie.START"
        const val RESUME="com.alex.rutasapie.RESUME"
        const val PAUSE="com.alex.rutasapie.PAUSE"
        const val FINISH="com.alex.rutasapie.FINISH"
        const val EXTRA_ID="walk_id"
        const val EXTRA_NAME="walk_name"
        private const val CHANNEL="walking_gps"
        private const val NOTIFICATION_ID=140
        fun hasLocationPermission(context: Context) = ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        fun Location.toFix() = GpsFix(latitude,longitude,accuracy.toDouble(),time,elapsedRealtimeNanos/1_000_000,
            if(hasAltitude()) altitude else null,if(hasSpeed()) speed.toDouble() else null)
        fun LocationManager.isLocationEnabledCompat(): Boolean = if(Build.VERSION.SDK_INT>=28) isLocationEnabled else
            isProviderEnabled(LocationManager.GPS_PROVIDER) || isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
