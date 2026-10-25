package com.alex.rutasapie

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogExpansionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun catalog() = context.assets.open("rutas.json").use { RouteCodec.decode(RouteCodec.read(it), false) }
    @Before fun clear() {
        context.getSharedPreferences("rutas_a_pie_prefs",0).edit().clear().commit()
        context.getSharedPreferences("rutas_a_pie_recuperacion",0).edit().clear().commit()
    }
    @Test fun allLegacyRoutesKeepTheirIdsAndContent() {
        val legacy = javaClass.getResourceAsStream("/legacy_catalog_1_4.json")!!.use { RouteCodec.decode(RouteCodec.read(it), false) }
        val expanded = catalog().associateBy { it.id }
        assertEquals(175, legacy.size)
        legacy.forEach { assertEquals(it, expanded[it.id]) }
    }
    @Test fun seventyFiveNewRoutesExtendCoverageToEverySpanishProvince() {
        val routes = catalog(); val new = routes.filter { it.id in 1051..1125 }
        assertEquals(400, routes.size); assertEquals(400, routes.map { it.id }.distinct().size)
        assertEquals(75, new.size)
        assertTrue(setOf("A Coruña", "Álava", "Almería", "Cádiz", "Córdoba", "Granada", "Gipuzkoa", "Huelva", "Jaén", "Lugo", "Málaga", "Ourense", "Pontevedra", "Sevilla", "Bizkaia").all { province -> routes.any { it.provincia == province } })
        assertEquals(RouteGeography.communities.toSet(), routes.map(RouteGeography::community).toSet())
        assertTrue(new.all { it.comunidad.isNotBlank() && it.fuente.isNotBlank() && RouteCodec.validSource(it.fuente) })
        assertEquals(53, routes.map { it.provincia }.distinct().size)
    }
    @Test fun communityProvinceAndAccentedSearchCombine() {
        val route = catalog().first { it.id == 1001 }
        assertTrue(RouteLogic.matches(route, "gredos", "Castilla y Leon"))
        assertTrue(RouteLogic.matches(route, "CASTILLA Y LEON", "avila"))
        assertFalse(RouteLogic.matches(route, "gredos", "Aragón"))
        assertTrue("Castilla y León" in RouteGeography.filters(catalog()))
        assertTrue("Ávila" in RouteGeography.filters(catalog()))
    }
    @Test fun newMetadataRoundTripsWithoutChangingLegacyJson() {
        val new = catalog().first { it.id == 1001 }
        assertEquals(listOf(new), RouteCodec.decode(RouteCodec.encode(listOf(new)), false))
        val old = catalog().first { it.id == 1 }
        assertFalse(RouteCodec.json(old).has("comunidad"))
        assertFalse(RouteCodec.json(old).has("fuente"))
    }
    @Test fun rejectsExecutableAndCredentialBearingSourceUrls() {
        for (url in listOf("javascript:alert(1)", "intent://route", "http://example.org/", "https://user:pass@example.org/", "https:///invalid")) {
            val json = """[{"nombre":"Ruta","fuente":"$url"}]"""
            try { RouteCodec.decode(json, true); fail("Accepted $url") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun expansionPreservesSavedMarksAndImportedRoutes() {
        val prefs = context.getSharedPreferences("rutas_a_pie_prefs",0)
        val imported = catalog().first { it.id == 1001 }.copy(id=6051, nombre="Mi paseo", importada=true)
        prefs.edit().putStringSet("favoritos_rutas",setOf("1","26","6051"))
            .putStringSet("rutas_realizadas",setOf("2","3001"))
            .putString("rutas_importadas_json",RouteCodec.encode(listOf(imported))).commit()
        val store = RouteStore(context)
        assertEquals(listOf(imported), store.load(catalog())); assertFalse(store.blocked)
        store.save(listOf(imported))
        assertEquals(listOf(imported), RouteStore(context).load(catalog()))
        assertEquals(setOf("1","26","6051"),prefs.getStringSet("favoritos_rutas",null))
        assertEquals(setOf("2","3001"),prefs.getStringSet("rutas_realizadas",null))
        assertEquals(6052, store.nextId())
    }
}
