package com.alex.rutasapie
import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RouteStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs get()=context.getSharedPreferences("rutas_a_pie_prefs",0)
    private val recovery get()=context.getSharedPreferences("rutas_a_pie_recuperacion",0)
    private fun route(id:Int=5001)=Ruta(id,"Madrid","Paseo","Lugar","","2 km","","1 h","Fácil","","","",emptyList(),true)
    @Before fun clear(){prefs.edit().clear().commit();recovery.edit().clear().commit()}
    @Test fun savesAndLoads(){val s=RouteStore(context);s.load(emptyList());s.save(listOf(route()));assertEquals(listOf(route()),RouteStore(context).load(emptyList()))}
    @Test fun preservesLegacyMarks(){prefs.edit().putStringSet("favoritos_rutas",setOf("1")).commit();val s=RouteStore(context);s.load(emptyList());s.save(listOf(route()));assertEquals(setOf("1"),prefs.getStringSet("favoritos_rutas",null))}
    @Test fun damagedOriginalNotOverwritten(){prefs.edit().putString("rutas_importadas_json","{broken").commit();val s=RouteStore(context);assertTrue(s.load(emptyList()).isEmpty());assertTrue(s.blocked);assertEquals("{broken",prefs.getString("rutas_importadas_json",null));assertEquals("{broken",recovery.getString("original_danado",null))}
    @Test(expected=IllegalStateException::class) fun damagedDataBlocksSave(){prefs.edit().putString("rutas_importadas_json","!").commit();val s=RouteStore(context);s.load(emptyList());s.save(emptyList())}
    @Test fun previousSnapshotRecoveredWithoutOverwrite(){recovery.edit().putString("anterior",RouteCodec.encode(listOf(route()))).commit();prefs.edit().putString("rutas_importadas_json","bad").commit();val s=RouteStore(context);assertEquals(listOf(route()),s.load(emptyList()));assertTrue(s.blocked);assertEquals("bad",prefs.getString("rutas_importadas_json",null))}
    @Test fun deletingCleansOnlyItsMarks(){prefs.edit().putStringSet("favoritos_rutas",setOf("1","5001")).putStringSet("rutas_realizadas",setOf("2","5001")).commit();val s=RouteStore(context);s.load(emptyList());s.save(listOf(route()));s.save(emptyList(),setOf(5001));assertEquals(setOf("1"),prefs.getStringSet("favoritos_rutas",null));assertEquals(setOf("2"),prefs.getStringSet("rutas_realizadas",null))}
    @Test fun idsNotReusedAfterDelete(){val s=RouteStore(context);s.load(emptyList());s.save(listOf(route(6000)));s.save(emptyList(),setOf(6000));assertEquals(6001,s.nextId())}
    @Test fun duplicatePersistedIdsBlocked(){prefs.edit().putString("rutas_importadas_json",RouteCodec.encode(listOf(route(),route()))).commit();val s=RouteStore(context);s.load(emptyList());assertTrue(s.blocked)}
    @Test fun baseCollisionBlocked(){prefs.edit().putString("rutas_importadas_json",RouteCodec.encode(listOf(route()))).commit();val s=RouteStore(context);s.load(listOf(route().copy(importada=false)));assertTrue(s.blocked)}
    @Test fun all400AssetsValidated(){val r=context.assets.open("rutas.json").use{RouteCodec.decode(RouteCodec.read(it),false)};assertEquals(400,r.size);assertEquals(400,r.map{it.id}.distinct().size);assertEquals(53,r.map{it.provincia}.distinct().size)}
    @Test fun explicitlyRestoresPriorSnapshot() {
        recovery.edit().putString("anterior",RouteCodec.encode(listOf(route()))).commit()
        prefs.edit().putString("rutas_importadas_json","broken").putStringSet("favoritos_rutas",setOf("5001")).commit()
        val store=RouteStore(context);store.load(emptyList());assertEquals(1,store.restorePrevious(emptyList()))
        assertFalse(store.blocked);assertEquals(listOf(route()),RouteStore(context).load(emptyList()))
        assertEquals("broken",recovery.getString("original_danado",null));assertEquals(setOf("5001"),prefs.getStringSet("favoritos_rutas",null))
    }
}
