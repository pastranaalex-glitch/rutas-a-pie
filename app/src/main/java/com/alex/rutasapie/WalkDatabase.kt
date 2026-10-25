package com.alex.rutasapie

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Private SQLite, transactional point+metrics writes. No location data in Android cloud backup. */
class WalkDatabase private constructor(context: Context) : SQLiteOpenHelper(context, "walks.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE walks(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,started INTEGER NOT NULL,status TEXT NOT NULL,distance REAL NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT,walk_id INTEGER NOT NULL REFERENCES walks(id) ON DELETE CASCADE,lat REAL NOT NULL,lon REAL NOT NULL,time INTEGER NOT NULL,accuracy REAL NOT NULL,altitude REAL,segment INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX point_walk ON points(walk_id,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Migración no definida: $oldVersion a $newVersion") }
    private fun transaction(block: (SQLiteDatabase)->Unit) {
        val db=writableDatabase; db.beginTransaction()
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun create(name: String, time: Long): Long = writableDatabase.insertOrThrow("walks",null,ContentValues().apply {
        put("name",name.take(200)); put("started",time); put("status","paused")
    })
    fun session(id: Long): WalkSession? = readableDatabase.rawQuery(
        "SELECT w.*, (SELECT COUNT(*) FROM points p WHERE p.walk_id=w.id) AS count FROM walks w WHERE w.id=?",arrayOf(id.toString())
    ).use { if(it.moveToFirst()) sessionFrom(it) else null }
    fun sessions(): List<WalkSession> = readableDatabase.rawQuery(
        "SELECT w.*, (SELECT COUNT(*) FROM points p WHERE p.walk_id=w.id) AS count FROM walks w ORDER BY w.started DESC,w.id DESC",null
    ).use { c -> buildList { while(c.moveToNext()) add(sessionFrom(c)) } }
    private fun sessionFrom(c: Cursor) = WalkSession(
        c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("name")),
        c.getLong(c.getColumnIndexOrThrow("started")),c.getString(c.getColumnIndexOrThrow("status")),
        c.getDouble(c.getColumnIndexOrThrow("distance")),c.getLong(c.getColumnIndexOrThrow("duration")),c.getInt(c.getColumnIndexOrThrow("count"))
    )
    fun points(id: Long): List<TrackPoint> = readableDatabase.rawQuery(
        "SELECT lat,lon,time,accuracy,altitude,segment FROM points WHERE walk_id=? ORDER BY id",arrayOf(id.toString())
    ).use { c -> buildList { while(c.moveToNext()) add(TrackPoint(c.getDouble(0),c.getDouble(1),c.getLong(2),c.getDouble(3),if(c.isNull(4)) null else c.getDouble(4),c.getInt(5))) } }
    fun update(id: Long, status: String, distance: Double, duration: Long, point: TrackPoint? = null) = transaction { db ->
        if (point != null) insertPoint(db,id,point)
        check(db.update("walks",ContentValues().apply {
            put("status",status); put("distance",distance); put("duration",duration.coerceAtLeast(0))
        },"id=?",arrayOf(id.toString()))==1) { "La caminata ya no existe." }
    }
    private fun insertPoint(db: SQLiteDatabase, id: Long, p: TrackPoint) {
        db.insertOrThrow("points",null,ContentValues().apply {
            put("walk_id",id); put("lat",p.latitude); put("lon",p.longitude); put("time",p.timeMillis)
            put("accuracy",p.accuracy); if(p.altitude!=null) put("altitude",p.altitude) else putNull("altitude"); put("segment",p.segment)
        })
    }
    fun importTrack(name: String, points: List<TrackPoint>): Long {
        require(points.isNotEmpty()) { "El GPX no contiene puntos." }
        var id=0L
        transaction { db ->
            var distance=0.0; var duration=0L
            points.zipWithNext().forEach { (a,b) -> if(a.segment==b.segment) {
                distance+=GeoMath.distance(a.latitude,a.longitude,b.latitude,b.longitude)
                if(a.timeMillis>0 && b.timeMillis>a.timeMillis) duration+=b.timeMillis-a.timeMillis
            } }
            id=db.insertOrThrow("walks",null,ContentValues().apply {
                put("name",name.take(200)); put("started",points.first().timeMillis.takeIf {it>0} ?: System.currentTimeMillis())
                put("status","imported"); put("distance",distance); put("duration",duration)
            })
            points.forEach { insertPoint(db,id,it) }
        }
        return id
    }
    fun recoverInterrupted() { writableDatabase.execSQL("UPDATE walks SET status='paused' WHERE status='recording'") }
    fun delete(id: Long) { writableDatabase.delete("walks","id=?",arrayOf(id.toString())) }
    companion object {
        @Volatile private var instance: WalkDatabase?=null
        fun get(context: Context): WalkDatabase = instance ?: synchronized(this) {
            instance ?: WalkDatabase(context.applicationContext).also { instance=it }
        }
    }
}
