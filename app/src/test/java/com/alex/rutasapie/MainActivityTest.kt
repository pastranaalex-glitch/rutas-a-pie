package com.alex.rutasapie
import android.os.Bundle
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.Duration
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MainActivityTest {
    @Before fun clear() {
        val c=org.robolectric.RuntimeEnvironment.getApplication()
        c.getSharedPreferences("rutas_a_pie_prefs",0).edit().clear().commit()
        c.getSharedPreferences("rutas_a_pie_recuperacion",0).edit().clear().commit()
    }
    private fun field(activity: MainActivity, name:String):Any {
        val f=MainActivity::class.java.getDeclaredField(name);f.isAccessible=true;return requireNotNull(f.get(activity))
    }
    @Test fun launchesCompleteCatalog(){val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();assertEquals(400,(field(a,"todasRutas") as List<*>).size);c.pause().stop().destroy()}
    @Test fun restoredSearchAndOrder(){val b=Bundle().apply{putString("busqueda","penalara");putString("orden","Duración");putString("zona","Madrid")};val c=Robolectric.buildActivity(MainActivity::class.java).create(b).start().resume();val a=c.get();assertEquals("penalara",(field(a,"edtBuscar") as EditText).text.toString());assertEquals("Duración",field(a,"ordenActual"));c.pause().stop().destroy()}
    @Test fun accentedSearchWorks(){val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();(field(a,"edtBuscar") as EditText).setText("penalara");shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(200));val view=field(a,"txtResumen") as android.widget.TextView;assertTrue(view.text.toString().startsWith("2 rutas"));c.pause().stop().destroy()}
    @Test fun invalidNameKeepsFormOpen() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        val method=MainActivity::class.java.getDeclaredMethod("mostrarFormularioAnadirRuta");method.isAccessible=true;method.invoke(a)
        val dialog=field(a,"formulario") as android.app.AlertDialog
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing);assertEquals(400,(field(a,"todasRutas") as List<*>).size)
        dialog.dismiss();c.pause().stop().destroy()
    }
    @Test fun addFormDraftSurvivesRecreation() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        val method=MainActivity::class.java.getDeclaredMethod("mostrarFormularioAnadirRuta");method.isAccessible=true;method.invoke(a)
        @Suppress("UNCHECKED_CAST") val fields=field(a,"camposFormulario") as List<EditText>
        fields[1].setText("Mi borrador")
        val b=Bundle();c.saveInstanceState(b).pause().stop().destroy()
        val c2=Robolectric.buildActivity(MainActivity::class.java).create(b).start().resume()
        @Suppress("UNCHECKED_CAST") val restored=field(c2.get(),"camposFormulario") as List<EditText>
        assertEquals("Mi borrador",restored[1].text.toString())
        (field(c2.get(),"formulario") as android.app.AlertDialog).dismiss();c2.pause().stop().destroy()
    }
    @Test fun favoritesFilterUsesExistingSavedMarks() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("rutas_a_pie_prefs",0).edit().putStringSet("favoritos_rutas",setOf("1","26")).commit()
        val b=Bundle().apply {putString("marcas","Favoritas")}
        val c=Robolectric.buildActivity(MainActivity::class.java).create(b).start().resume()
        assertTrue((field(c.get(),"txtResumen") as android.widget.TextView).text.toString().startsWith("2 rutas"))
        c.pause().stop().destroy()
    }
    @Test fun completedAndPendingFiltersAgree() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("rutas_a_pie_prefs",0).edit().putStringSet("rutas_realizadas",setOf("1","2")).commit()
        for((filter,n) in listOf("Realizadas" to 2,"Pendientes" to 398)) {
            val c=Robolectric.buildActivity(MainActivity::class.java).create(Bundle().apply {putString("marcas",filter)}).start().resume()
            assertTrue((field(c.get(),"txtResumen") as android.widget.TextView).text.toString().startsWith("$n rutas"))
            c.pause().stop().destroy()
        }
    }
    @Test fun catalogueGpsActionOpensIntegratedScreenWithRouteName() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        val method=MainActivity::class.java.getDeclaredMethod("abrirGps",Ruta::class.java);method.isAccessible=true
        @Suppress("UNCHECKED_CAST") val route=(field(a,"todasRutas") as List<Ruta>).first()
        method.invoke(a,route)
        val intent=shadowOf(a).nextStartedActivity
        assertEquals(GpsActivity::class.java.name,intent.component?.className)
        assertEquals(route.nombre,intent.getStringExtra("route_name"))
        c.pause().stop().destroy()
    }
}
