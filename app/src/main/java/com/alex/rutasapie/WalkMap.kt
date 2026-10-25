package com.alex.rutasapie

import android.graphics.Color
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*
import kotlin.math.*

/** Native map. The recorder works independently of the renderer and connectivity. */
class WalkMap(private val view: MapView, private val ready: ()->Unit, private val failure: ()->Unit, restored:Boolean=false) {
    private var map: MapLibreMap?=null
    private var style: Style?=null
    var follow=true
    private var centeredOnce=restored
    private var track=emptyList<TrackPoint>()
    private var reference=emptyList<TrackPoint>()
    private var fix:GpsFix?=null
    private var fresh=false
    fun initialize() {
        view.addOnDidFailLoadingMapListener { failure() }
        view.getMapAsync { m ->
            map=m; m.uiSettings.isLogoEnabled=false; m.uiSettings.isAttributionEnabled=false
            m.setPrefetchZoomDelta(0) // Request the visible zoom level only, with no tile prefetch.
            m.uiSettings.isCompassEnabled=true
            m.setMinZoomPreference(3.0); m.setMaxZoomPreference(19.0)
            if(!centeredOnce) m.cameraPosition=CameraPosition.Builder().target(LatLng(40.4168,-3.7038)).zoom(5.0).build()
            m.addOnCameraMoveStartedListener { reason -> if(reason==MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow=false }
            m.setStyle(Style.Builder().fromJson(RASTER_STYLE)) { s ->
                style=s
                listOf("reference","trail","start","position","accuracy").forEach { id -> s.addSource(GeoJsonSource(id,FeatureCollection.fromFeatures(emptyArray<Feature>()))) }
                s.addLayer(FillLayer("accuracy-fill","accuracy").withProperties(fillColor("#1976D2"),fillOpacity(0.12f)))
                s.addLayer(LineLayer("reference-line","reference").withProperties(lineColor("#68748A"),lineWidth(3f),lineDasharray(arrayOf(2f,2f))))
                s.addLayer(LineLayer("trail-line","trail").withProperties(lineColor("#E57820"),lineWidth(4f),lineCap(Property.LINE_CAP_ROUND),lineJoin(Property.LINE_JOIN_ROUND)))
                s.addLayer(CircleLayer("start-circle","start").withProperties(circleColor("#16825D"),circleRadius(7f),circleStrokeColor(Color.WHITE),circleStrokeWidth(2f)))
                s.addLayer(CircleLayer("position-circle","position").withProperties(circleColor("#1976D2"),circleRadius(8f),circleStrokeColor(Color.WHITE),circleStrokeWidth(3f)))
                drawTrack(); drawReference(); drawFix(); ready()
            }
        }
    }
    fun setTrack(points: List<TrackPoint>) { track=points; drawTrack() }
    fun setReference(points: List<TrackPoint>) { reference=points; drawReference() }
    fun setPosition(position: GpsFix?, isFresh: Boolean) {
        if(fix==position && fresh==isFresh) return
        fix=position; fresh=isFresh; drawFix()
    }
    private fun drawTrack() {
        style?.getSourceAs<GeoJsonSource>("trail")?.setGeoJson(lines(track))
        val p=track.firstOrNull()
        style?.getSourceAs<GeoJsonSource>("start")?.setGeoJson(FeatureCollection.fromFeatures(if(p==null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(p.longitude,p.latitude)))))
    }
    private fun drawReference() { style?.getSourceAs<GeoJsonSource>("reference")?.setGeoJson(lines(reference)) }
    private fun lines(points: List<TrackPoint>): FeatureCollection {
        val stride=max(1,ceil(points.size/6000.0).toInt())
        val features=points.groupBy {it.segment}.values.mapNotNull { segment ->
            if(segment.size<2) null else {
                val simplified=segment.filterIndexed { i,_ -> i==0 || i==segment.lastIndex || i%stride==0 }
                Feature.fromGeometry(LineString.fromLngLats(simplified.map {Point.fromLngLat(it.longitude,it.latitude)}))
            }
        }
        return FeatureCollection.fromFeatures(features)
    }
    private fun drawFix() {
        val s=style ?: return; val f=fix
        s.getSourceAs<GeoJsonSource>("position")?.setGeoJson(FeatureCollection.fromFeatures(if(f==null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(f.longitude,f.latitude)))))
        s.getLayerAs<CircleLayer>("position-circle")?.setProperties(circleColor(if(fresh) "#1976D2" else "#7E8794"))
        if(f!=null) {
            val radius=f.accuracy.coerceAtMost(50_000.0)
            val ring=(0..48).map { i ->
                val a=2*PI*i/48
                Point.fromLngLat(f.longitude+cos(a)*radius/(111_320*max(0.01,cos(Math.toRadians(f.latitude)))),(f.latitude+sin(a)*radius/111_320).coerceIn(-85.0,85.0))
            }
            s.getSourceAs<GeoJsonSource>("accuracy")?.setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
            if(follow && fresh) {
                val zoom=if(!centeredOnce) 16.0 else map?.cameraPosition?.zoom ?: 16.0
                centeredOnce=true; map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.latitude,f.longitude),zoom))
            }
        } else s.getSourceAs<GeoJsonSource>("accuracy")?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }
    fun locate(): Boolean {
        val f=fix ?: return false; follow=true; centeredOnce=true
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.latitude,f.longitude),16.0)); return true
    }
    fun showStart(): Boolean {
        val p=track.firstOrNull() ?: return false; follow=false; centeredOnce=true
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude,p.longitude),16.0)); return true
    }
    fun fit(points: List<TrackPoint> = track) {
        if(points.isEmpty()) return; follow=false; centeredOnce=true
        if(points.size==1) { val p=points.first(); map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude,p.longitude),16.0)); return }
        val bounds=LatLngBounds.Builder().includes(points.map {LatLng(it.latitude,it.longitude)}).build()
        view.post { map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds,48)) }
    }
    companion object {
        private const val RASTER_STYLE="""{"version":8,"name":"Rutas a Pie · OpenStreetMap","sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"minzoom":0,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#e5ece7"}},{"id":"osm","type":"raster","source":"osm","paint":{"raster-fade-duration":0}}]}"""
    }
}
