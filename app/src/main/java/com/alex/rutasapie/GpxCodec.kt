package com.alex.rutasapie

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

data class GpxTrack(val name: String, val points: List<TrackPoint>)

object GpxCodec {
    const val MAX_BYTES=10*1024*1024
    const val MAX_POINTS=100_000
    /** Local GPX only; DTD/entity declarations are rejected before XML parsing. */
    fun read(input: InputStream): GpxTrack {
        val data=java.io.ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            val n=input.read(buffer); if(n<0) break
            require(data.size()+n<=MAX_BYTES) { "El GPX supera 10 MB." }; data.write(buffer,0,n)
        }
        val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data.toByteArray())).toString().removePrefix("\uFEFF")
        require(!text.contains("<!DOCTYPE",true) && !text.contains("<!ENTITY",true)) { "GPX con declaraciones XML no permitidas." }
        require(!text.contains('\u0000')) { "El GPX debe estar codificado en UTF-8." }
        val parser=XmlPullParserFactory.newInstance().apply { isNamespaceAware=true }.newPullParser()
        parser.setInput(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),"UTF-8")
        val points=mutableListOf<TrackPoint>()
        var root=false; var segment=-1; var current: TrackPoint?=null; var pointDepth=-1; var name="Recorrido GPX"
        var sawName=false; var event=parser.eventType
        while(event!=XmlPullParser.END_DOCUMENT) {
            when(event) {
                XmlPullParser.START_TAG -> when(parser.name) {
                    "gpx" -> { require(parser.depth==1) { "Raíz GPX inválida." }; root=true }
                    "trk", "rte" -> { if(parser.name=="rte") segment++ }
                    "trkseg" -> segment++
                    "trkpt", "rtept" -> {
                        require(root && current==null) { "Estructura GPX inválida." }
                        val lat=parser.getAttributeValue(null,"lat")?.toDoubleOrNull()
                        val lon=parser.getAttributeValue(null,"lon")?.toDoubleOrNull()
                        require(lat!=null && lon!=null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) { "Coordenadas GPX inválidas." }
                        current=TrackPoint(lat,lon,0,0.0,null,segment.coerceAtLeast(0)); pointDepth=parser.depth
                    }
                    "ele" -> if(current!=null && parser.depth==pointDepth+1) {
                        val elevation=parser.nextText().toDoubleOrNull()
                        require(elevation!=null && elevation.isFinite()) { "Altitud GPX inválida." }; current=current.copy(altitude=elevation)
                    }
                    "time" -> if(current!=null && parser.depth==pointDepth+1) {
                        val t=parser.nextText()
                        val ms=try { Instant.parse(t).toEpochMilli() } catch (_: Exception) { throw IllegalArgumentException("Fecha GPX inválida.") }
                        current=current.copy(timeMillis=ms.coerceAtLeast(0))
                    }
                    "name" -> if(current==null && !sawName) { name=parser.nextText().trim().take(200).ifBlank {"Recorrido GPX"}; sawName=true }
                }
                XmlPullParser.END_TAG -> if(parser.name in setOf("trkpt","rtept") && current!=null) {
                    require(points.size<MAX_POINTS) { "El GPX supera 100 000 puntos." }; points.add(current); current=null
                }
                XmlPullParser.DOCDECL -> error("DTD no permitido.")
            }
            event=parser.next()
        }
        require(root && points.isNotEmpty()) { "El archivo no contiene un trazado GPX (trkpt o rtept)." }
        return GpxTrack(name,points)
    }
    private fun escape(text: String) = text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    fun write(output: OutputStream, name: String, points: List<TrackPoint>) {
        require(points.isNotEmpty()) { "No hay puntos para exportar." }
        // Do not close a caller-owned stream. Export a stable snapshot and preserve pause/gap segments.
        val writer=output.bufferedWriter(Charsets.UTF_8)
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Rutas a Pie\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>${escape(name)}</name>\n")
        var segment:Int?=null
        for(p in points) {
            if(segment!=p.segment) { if(segment!=null) writer.write("</trkseg>\n"); writer.write("<trkseg>\n"); segment=p.segment }
            writer.write("<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">")
            p.altitude?.let {writer.write("<ele>$it</ele>")}
            if(p.timeMillis>0) writer.write("<time>${Instant.ofEpochMilli(p.timeMillis)}</time>")
            writer.write("</trkpt>\n")
        }
        writer.write("</trkseg></trk></gpx>\n"); writer.flush()
    }
}
