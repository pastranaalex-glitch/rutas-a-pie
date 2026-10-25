package com.alex.rutasapie

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.*
import android.net.Uri
import androidx.core.net.toUri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import java.text.SimpleDateFormat
import java.util.*
import com.alex.rutasapie.TrackingService.Companion.isLocationEnabledCompat
import com.alex.rutasapie.TrackingService.Companion.toFix

class GpsActivity : ComponentActivity(), LocationListener {
    private lateinit var mapView: MapView
    private lateinit var walkMap: WalkMap
    private lateinit var status: TextView
    private lateinit var precision: TextView
    private lateinit var distance: TextView
    private lateinit var duration: TextView
    private lateinit var speed: TextView
    private lateinit var average: TextView
    private lateinit var coordinates: TextView
    private lateinit var record: Button
    private lateinit var finishButton: Button
    private lateinit var manager: LocationManager
    private val db by lazy { WalkDatabase.get(this) }
    private var selected: WalkSession?=null
    private var points=emptyList<TrackPoint>()
    private var reference=emptyList<TrackPoint>()
    private var latest:GpsFix?=null
    private var pendingRecord=false
    private var preview=false
    private var loaded=false
    private var resumed=false
    private var loadJob: Job?=null
    private var displayedCount=-1
    private var displayedId=0L
    private var exportId=0L
    private var referenceId=0L
    private var lastServiceError=""
    private val locale=Locale.forLanguageTag("es-ES")
    private val main=Handler(Looper.getMainLooper())

    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(TrackingService.hasLocationPermission(this)) {
            if(pendingRecord) {pendingRecord=false; startRecording()} else startPreview()
        } else {
            pendingRecord=false; status.text=getString(R.string.gps_permission_denied)
            AlertDialog.Builder(this).setTitle(R.string.gps_permission_title).setMessage(R.string.gps_permission_denied)
                .setPositiveButton(R.string.open_settings) {_,_ -> openAppSettings()}.setNegativeButton(R.string.close,null).show()
        }
    }
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) {startRecordingWithPermission()}
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val id=exportId
        exportId=0
        if(uri!=null && id>0) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val s=db.session(id) ?: kotlin.error("La caminata ya no existe.")
                    val snapshot=db.points(id)
                    val stream=contentResolver.openOutputStream(uri,"wt") ?: kotlin.error("No se pudo abrir el destino.")
                    stream.use {GpxCodec.write(it,s.name,snapshot)}
                }
                toast(getString(R.string.gpx_exported))
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {showError("Exportación incompleta: ${e.message}. Revisa el archivo antes de usarlo.")}
        }
    }
    private val import=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null) lifecycleScope.launch {
            try {
                val track=withContext(Dispatchers.IO) {contentResolver.openInputStream(uri)?.use {GpxCodec.read(it)} ?: kotlin.error("No se pudo abrir el archivo.")}
                AlertDialog.Builder(this@GpsActivity).setTitle(R.string.gpx_import_title)
                    .setMessage(getString(R.string.gpx_import_confirm,track.name,track.points.size))
                    .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.import_action) {_,_ -> saveImported(track)}.show()
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {showError("No se importó ningún dato: ${e.message}")}
        }
    }
    private fun saveImported(track:GpxTrack)=lifecycleScope.launch {
        try {
            val id=withContext(Dispatchers.IO) {db.importTrack(track.name,track.points)}
            if(TrackingRuntime.active) {referenceId=id; reference=track.points; walkMap.setReference(reference); walkMap.fit(reference); toast(getString(R.string.gpx_reference))}
            else selectSession(id,true)
        } catch(e: CancellationException) {throw e}
        catch(e: Exception) {showError("No se pudo guardar el GPX: ${e.message}")}
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportId=savedInstanceState?.getLong("export_id",0) ?: 0
        referenceId=savedInstanceState?.getLong("reference_id",0) ?: 0
        manager=getSystemService(LOCATION_SERVICE) as LocationManager
        MapLibre.getInstance(this)
        // Debe configurarse antes de inflar MapView: los mapas son un recurso externo.
        MapNetworkConfig.install(this)
        setContentView(R.layout.activity_gps)
        mapView=findViewById(R.id.gps_map); mapView.onCreate(savedInstanceState)
        status=findViewById(R.id.gps_status); precision=findViewById(R.id.gps_precision)
        distance=findViewById(R.id.gps_distance); duration=findViewById(R.id.gps_duration)
        speed=findViewById(R.id.gps_speed); average=findViewById(R.id.gps_average)
        coordinates=findViewById(R.id.gps_coordinates); record=findViewById(R.id.gps_record); finishButton=findViewById(R.id.gps_finish)
        val root=findViewById<View>(R.id.gps_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) {v,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        WindowCompat.getInsetsController(window,root).apply {isAppearanceLightStatusBars=true; isAppearanceLightNavigationBars=true}
        walkMap=WalkMap(mapView,{walkMap.setTrack(points); walkMap.setReference(reference); latest?.let {walkMap.setPosition(it,isFresh(it))}}, {toast(getString(R.string.map_load_failed))},savedInstanceState!=null)
        walkMap.follow=savedInstanceState?.getBoolean("follow",true) ?: true
        walkMap.initialize()
        findViewById<View>(R.id.gps_back).setOnClickListener {finish()}
        findViewById<View>(R.id.gps_history).setOnClickListener {showHistory()}
        findViewById<View>(R.id.gps_locate).setOnClickListener {
            if(!TrackingService.hasLocationPermission(this)) requestLocationPermissions()
            else if(!walkMap.locate()) {requestPreview(); toast(getString(R.string.gps_waiting))}
        }
        findViewById<View>(R.id.gps_start).setOnClickListener {showStart()}
        findViewById<View>(R.id.gps_more).setOnClickListener {showMore()}
        findViewById<View>(R.id.gps_attribution).setOnClickListener {openWeb("https://www.openstreetmap.org/copyright")}
        record.setOnClickListener {
            if(TrackingRuntime.active && TrackingRuntime.state.value.session?.status=="recording") command(TrackingService.PAUSE) else startRecording()
        }
        finishButton.setOnClickListener {confirmFinish()}
        record.isEnabled=false; finishButton.isEnabled=false
        lifecycleScope.launch {
            try {
                val id=withContext(Dispatchers.IO) {
                    if(!TrackingRuntime.active) db.recoverInterrupted()
                    savedInstanceState?.getLong("selected",0)?.takeIf {it>0} ?: db.sessions().firstOrNull()?.id
                }
                loaded=true
                if(TrackingRuntime.active) updateState(TrackingRuntime.state.value) else if(id!=null) selectSession(id,false) else render()
                if(referenceId>0 && TrackingRuntime.active) {
                    reference=withContext(Dispatchers.IO) {db.points(referenceId)}
                    walkMap.setReference(reference)
                }
                requestPreview()
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {showError("No se pudo abrir el historial: ${e.message}")}
        }
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED) {TrackingRuntime.state.collect {if(loaded) updateState(it)}}}
    }
    private fun updateState(state: TrackingState) {
        val failure=state.message
        if((failure.startsWith("Grabación detenida") || failure.contains("no permitió iniciar") ||
            failure.startsWith("Permiso de ubicación retirado")) && failure!=lastServiceError) {
            lastServiceError=failure; showError(failure)
        }
        if(state.live && state.session!=null || TrackingRuntime.active) {
            val live=state.session
            if(live!=null) {selected=live; loadPoints(live.id,live.pointCount,false)}
            if(preview) stopPreview()
            latest=state.fix; status.text=state.message
        } else if(state.session!=null && selected?.id==state.session.id) {
            selected=state.session; status.text=state.message; loadPoints(state.session.id,state.session.pointCount,false)
            if(resumed) startPreview()
        }
        render()
    }
    private fun selectSession(id:Long,fit:Boolean) {
        loadJob?.cancel()
        loadJob=lifecycleScope.launch {
            try {
                val result=withContext(Dispatchers.IO) {db.session(id) to db.points(id)}
                if(TrackingRuntime.active) return@launch
                selected=result.first; points=result.second; displayedId=id; displayedCount=points.size
                referenceId=0; reference=emptyList(); walkMap.setReference(reference); walkMap.setTrack(points)
                if(fit) walkMap.fit(); render()
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {showError("No se pudo leer la caminata: ${e.message}")}
        }
    }
    private fun loadPoints(id:Long,count:Int,fit:Boolean) {
        if(id==displayedId && count==displayedCount || loadJob?.isActive==true) return
        loadJob=lifecycleScope.launch {
            try {
                val list=withContext(Dispatchers.IO) {db.points(id)}
                if(selected?.id!=id) return@launch
                points=list; displayedId=id; displayedCount=list.size; walkMap.setTrack(points)
                if(fit) walkMap.fit()
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {showError("No se pudo mostrar el trazado: ${e.message}")}
        }
    }
    private fun startRecording() {
        if(!loaded) return
        if(!TrackingService.hasLocationPermission(this)) {pendingRecord=true; requestLocationPermissions(); return}
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        startRecordingWithPermission()
    }
    private fun startRecordingWithPermission() {
        if(!TrackingService.hasLocationPermission(this)) {pendingRecord=true; requestLocationPermissions(); return}
        if(!manager.isLocationEnabledCompat()) {
            AlertDialog.Builder(this).setTitle(R.string.location_disabled).setMessage(R.string.location_enable_message)
                .setPositiveButton(R.string.open_settings) {_,_ -> try {startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))} catch(_:Exception) {toast(getString(R.string.settings_failed))}}
                .setNegativeButton(R.string.cancel,null).show(); return
        }
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle(R.string.precise_required_title).setMessage(R.string.precise_required_message)
                .setPositiveButton(R.string.open_settings) {_,_ -> openAppSettings()}.setNegativeButton(R.string.cancel,null).show(); return
        }
        if(TrackingRuntime.active) {command(TrackingService.RESUME); return}
        val s=selected
        if(s?.status=="paused") {
            AlertDialog.Builder(this).setTitle(R.string.resume_title).setMessage(s.name)
                .setPositiveButton(R.string.resume_action) {_,_ -> begin(s.id,s.name)}
                .setNeutralButton(R.string.new_walk) {_,_ -> newWalk()}.setNegativeButton(R.string.cancel,null).show()
        } else newWalk()
    }
    private fun newWalk() {
        val field=EditText(this).apply {setText(intent.getStringExtra("route_name") ?: "Caminata ${SimpleDateFormat("dd/MM HH:mm",locale).format(Date())}"); maxLines=2; selectAll()}
        AlertDialog.Builder(this).setTitle(R.string.new_walk).setMessage(R.string.walk_name_prompt).setView(field)
            .setPositiveButton(R.string.record_action) {_,_ -> begin(0,field.text.toString().trim().ifBlank {"Mi caminata"}.take(200))}
            .setNegativeButton(R.string.cancel,null).show()
    }
    private fun begin(id:Long,name:String) {
        if(TrackingRuntime.active) {toast(getString(R.string.walk_already_active)); return}
        stopPreview(); referenceId=0; reference=emptyList(); walkMap.setReference(reference)
        points=emptyList(); walkMap.setTrack(points); displayedId=0; displayedCount=-1; walkMap.follow=true
        try {
            ContextCompat.startForegroundService(this,Intent(this,TrackingService::class.java).setAction(if(id>0) TrackingService.RESUME else TrackingService.START)
                .putExtra(TrackingService.EXTRA_ID,id).putExtra(TrackingService.EXTRA_NAME,name))
            status.text=getString(R.string.gps_starting)
        } catch(e: Exception) {showError("No se pudo iniciar GPS: ${e.message}"); startPreview()}
    }
    private fun command(action:String) {
        if(!TrackingRuntime.active) return
        try {startService(Intent(this,TrackingService::class.java).setAction(action))} catch(e:Exception) {showError("No se pudo cambiar la grabación: ${e.message}")}
    }
    private fun confirmFinish() {
        if(!TrackingRuntime.active) return
        AlertDialog.Builder(this).setTitle(R.string.finish_title).setMessage(R.string.finish_message)
            .setPositiveButton(R.string.save_finish) {_,_ -> command(TrackingService.FINISH)}.setNegativeButton(R.string.cancel,null).show()
    }
    private fun requestLocationPermissions() {permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
    private fun requestPreview() {if(!TrackingRuntime.active && resumed && TrackingService.hasLocationPermission(this)) startPreview()}
    @Suppress("MissingPermission")
    private fun startPreview() {
        if(preview || !resumed || TrackingRuntime.active || !TrackingService.hasLocationPermission(this)) return
        try {
            var n=0
            for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)) {
                if(provider==LocationManager.GPS_PROVIDER && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) continue
                if(manager.allProviders.contains(provider) && manager.isProviderEnabled(provider)) {manager.requestLocationUpdates(provider,5_000L,0f,this,Looper.getMainLooper()); n++}
            }
            preview=n>0
            if(!preview) status.text=getString(R.string.location_disabled)
        } catch(_:SecurityException) {status.text=getString(R.string.gps_permission_denied)}
        catch(_:IllegalArgumentException) {status.text=getString(R.string.gps_provider_missing)}
    }
    private fun stopPreview() {
        if(preview) {
            try {manager.removeUpdates(this)} catch(_:SecurityException) { /* Android may revoke the grant while this screen is open. */ }
            finally {preview=false}
        }
    }
    override fun onLocationChanged(location:Location) {
        if(TrackingRuntime.active) return
        val f=location.toFix(); if(!f.isValid() || !isFresh(f)) return
        val old=latest
        if(old==null || f.accuracy<=old.accuracy || f.elapsedMillis-old.elapsedMillis>10_000) latest=f
        render()
    }
    override fun onProviderEnabled(provider:String) {stopPreview(); startPreview()}
    override fun onProviderDisabled(provider:String) {status.text=getString(R.string.location_disabled); render()}
    @Deprecated("Required for older LocationListener implementations")
    override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?)=Unit
    private fun isFresh(f:GpsFix)=SystemClock.elapsedRealtime()-f.elapsedMillis in -1000L..15_000L
    private fun render() {
        val s=selected; val f=latest; val fresh=f?.let(::isFresh)==true
        distance.text=getString(R.string.stat_distance,s?.distanceMeters?.div(1000) ?: 0.0)
        duration.text=getString(R.string.stat_duration,GeoMath.duration(s?.durationMillis ?: 0))
        speed.text=getString(R.string.stat_speed,if(fresh) (f?.speedMps ?: 0.0)*3.6 else 0.0)
        average.text=getString(R.string.stat_average,GeoMath.averageKmh(s?.distanceMeters ?: 0.0,s?.durationMillis ?: 0))
        precision.text=if(f==null) getString(R.string.gps_waiting) else getString(R.string.gps_precision,f.accuracy)+if(!fresh) " · ${getString(R.string.gps_old_fix)}" else ""
        precision.setTextColor(if(fresh && (f?.accuracy ?: 999.0)<=35) Color.rgb(22,104,72) else Color.rgb(132,80,24))
        coordinates.text=if(f==null) getString(R.string.gps_no_coordinates) else getString(R.string.gps_coordinates,f.latitude,f.longitude)
        walkMap.setPosition(f,fresh)
        val live=TrackingRuntime.active
        record.isEnabled=loaded; finishButton.isEnabled=live
        record.setText(if(live && s?.status=="recording") R.string.pause_action else if(s?.status=="paused") R.string.resume_action else R.string.record_action)
        if(!live) status.text=when(s?.status) {"paused"->getString(R.string.walk_paused,s.name); "finished"->s.name; "imported"->getString(R.string.walk_imported,s.name); else->if(TrackingService.hasLocationPermission(this)) getString(R.string.gps_ready) else getString(R.string.gps_permission_hint)}
    }
    private fun showStart() {
        val p=points.firstOrNull()
        if(p==null || !walkMap.showStart()) {toast(getString(R.string.no_start_point)); return}
        val f=latest
        val info=if(f!=null && isFresh(f)) String.format(locale,"Inicio a %.0f m en línea recta · rumbo %.0f°\n\n",GeoMath.distance(f.latitude,f.longitude,p.latitude,p.longitude),GeoMath.bearing(f.latitude,f.longitude,p.latitude,p.longitude)) else ""
        AlertDialog.Builder(this).setTitle(R.string.start_point_title).setMessage(info+getString(R.string.start_point_message)).setPositiveButton(R.string.understood,null).show()
    }
    private fun showHistory()=lifecycleScope.launch {
        try {
            val list=withContext(Dispatchers.IO) {db.sessions()}
            if(list.isEmpty()) {toast(getString(R.string.no_walks)); return@launch}
            val labels=list.map {s -> "${s.name}\n${SimpleDateFormat("dd/MM/yyyy HH:mm",locale).format(Date(s.startedAt))} · ${String.format(locale,"%.2f km",s.distanceMeters/1000)} · ${GeoMath.duration(s.durationMillis)} · ${when(s.status){"paused"->"Pausada";"recording"->"Grabando";"imported"->"GPX";else->"Guardada"}}"}.toTypedArray()
            AlertDialog.Builder(this@GpsActivity).setTitle(R.string.history_title).setItems(labels) {_,i ->
                val s=list[i]
                if(TrackingRuntime.active) {
                    if(s.id!=TrackingRuntime.state.value.session?.id) showReference(s.id) else walkMap.fit()
                } else selectSession(s.id,true)
            }.setNegativeButton(R.string.close,null).show()
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {showError("No se pudo leer el historial: ${e.message}")}
    }
    private fun showReference(id:Long)=lifecycleScope.launch {
        try {reference=withContext(Dispatchers.IO) {db.points(id)}; referenceId=id; walkMap.setReference(reference); walkMap.fit(reference); toast(getString(R.string.gpx_reference))}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {showError("No se pudo abrir el trazado: ${e.message}")}
    }
    private fun showMore() {
        val options=arrayOf(getString(R.string.export_gpx),getString(R.string.import_gpx),getString(R.string.view_track),getString(R.string.share_position),getString(R.string.delete_walk),getString(R.string.gps_help),getString(R.string.open_settings))
        AlertDialog.Builder(this).setTitle(R.string.gps_more_title).setItems(options) {_,i -> when(i) {
            0->exportGpx()
            1->try {import.launch(arrayOf("application/gpx+xml","application/xml","text/xml","application/octet-stream","*/*"))} catch(_:Exception) {showError(getString(R.string.file_picker_failed))}
            2->if(points.isEmpty()) toast(getString(R.string.no_track_points)) else walkMap.fit()
            3->sharePosition()
            4->deleteWalk()
            5->HelpActivity.open(this, "gps")
            6->openAppSettings()
        }}.setNegativeButton(R.string.close,null).show()
    }
    private fun exportGpx() {
        val s=selected ?: run {toast(getString(R.string.no_track_points)); return}
        if(s.pointCount==0) {toast(getString(R.string.no_track_points)); return}
        try {exportId=s.id; export.launch("RutasAPie_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(Date())}.gpx")}
        catch(e:Exception) {exportId=0; showError("No se pudo exportar: ${e.message}")}
    }
    private fun deleteWalk() {
        val s=selected ?: return
        if(TrackingRuntime.active) {toast(getString(R.string.finish_before_delete)); return}
        AlertDialog.Builder(this).setTitle(R.string.delete_walk).setMessage(getString(R.string.delete_walk_confirm,s.name))
            .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete_action) {_,_ -> lifecycleScope.launch {
                try {withContext(Dispatchers.IO) {db.delete(s.id)}; selected=null; points=emptyList(); displayedId=0; displayedCount=-1; walkMap.setTrack(points); render()}
                catch(e:CancellationException) {throw e}
                catch(e:Exception) {showError("No se pudo borrar: ${e.message}")}
            }}.show()
    }
    private fun sharePosition() {
        val f=latest
        if(f==null || !isFresh(f)) {toast(getString(R.string.gps_waiting)); return}
        val text=String.format(Locale.ROOT,"Mi posición: %.6f, %.6f (precisión ±%.0f m)\nhttps://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f",f.latitude,f.longitude,f.accuracy,f.latitude,f.longitude,f.latitude,f.longitude)
        try {startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),getString(R.string.share_position)))} catch(_:Exception) {toast(getString(R.string.share_failed))}
    }
    private fun openAppSettings() {try {startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",packageName,null)))} catch(_:Exception) {toast(getString(R.string.settings_failed))}}
    private fun openWeb(url:String) {try {startActivity(Intent(Intent.ACTION_VIEW,url.toUri()))} catch(_:Exception) {toast(getString(R.string.browser_failed))}}
    private fun showError(text:String) {if(!isFinishing) AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(text).setPositiveButton(R.string.understood,null).show()}
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private val refresh=object:Runnable {override fun run() {if(resumed) {render(); main.postDelayed(this,1_000)}}}
    override fun onStart() {super.onStart(); mapView.onStart()}
    override fun onResume() {super.onResume(); resumed=true; mapView.onResume(); requestPreview(); main.post(refresh)}
    override fun onPause() {resumed=false; main.removeCallbacks(refresh); stopPreview(); mapView.onPause(); super.onPause()}
    override fun onStop() {mapView.onStop(); super.onStop()}
    override fun onLowMemory() {super.onLowMemory(); mapView.onLowMemory()}
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putLong("selected",selected?.id ?: 0); outState.putLong("export_id",exportId)
        outState.putLong("reference_id",referenceId); outState.putBoolean("follow",walkMap.follow)
        mapView.onSaveInstanceState(outState); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {main.removeCallbacksAndMessages(null); stopPreview(); mapView.onDestroy(); super.onDestroy()}
}
