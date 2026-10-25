package com.alex.rutasapie

import android.content.Context
import android.annotation.SuppressLint

class RouteStore(context: Context) {
    private val prefs = context.getSharedPreferences("rutas_a_pie_prefs", Context.MODE_PRIVATE)
    private val recovery = context.getSharedPreferences("rutas_a_pie_recuperacion", Context.MODE_PRIVATE)
    var blocked = false
        private set
    var notice = ""
        private set
    fun load(base: List<Ruta>): List<Ruta> {
        blocked = false; notice = ""
        val raw = prefs.all["rutas_importadas_json"]
        try {
            require(raw == null || raw is String) { "Tipo de datos guardados inválido." }
            val routes = RouteCodec.decode(raw as? String ?: "[]", true)
            require(routes.map { it.id }.distinct().size == routes.size && routes.all { it.id > 0 } &&
                routes.none { r -> base.any { it.id == r.id } }) { "Identificadores guardados duplicados." }
            require(base.size + routes.size <= RouteCodec.MAX_ROUTES) { "Catálogo demasiado grande." }
            return routes
        } catch (e: Exception) {
            blocked = true
            val protected = recovery.edit().putString("original_danado", raw?.toString().orEmpty())
                .putLong("fecha", System.currentTimeMillis()).commit()
            notice = "Hay rutas guardadas dañadas. Se bloquean cambios para protegerlas." +
                if (protected) " Se conserva el original para recuperación." else " No se pudo guardar la copia de recuperación."
            val previous = recovery.all["anterior"] as? String
            if (previous != null) try {
                val routes = RouteCodec.decode(previous, true)
                require(routes.map { it.id }.distinct().size == routes.size && routes.all { it.id > 0 } &&
                    routes.none { r -> base.any { it.id == r.id } } && base.size + routes.size <= RouteCodec.MAX_ROUTES)
                notice += " Se muestra la copia anterior; puedes exportarla."
                return routes
            } catch (_: Exception) { /* Original remains untouched and editing remains blocked. */ }
            return emptyList()
        }
    }
    // Checked commit and rollback protect the damaged original; KTX edit discards this result.
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun restorePrevious(base: List<Ruta>): Int {
        check(blocked) { "No hay datos dañados que recuperar." }
        val raw = prefs.all["rutas_importadas_json"]?.toString().orEmpty()
        check(recovery.edit().putString("original_danado", raw).commit()) { "No se pudo proteger el original." }
        val previous = recovery.all["anterior"] as? String ?: error("No existe una copia anterior.")
        val routes = RouteCodec.decode(previous, true)
        require(routes.map { it.id }.distinct().size == routes.size && routes.all { it.id > 0 } &&
            routes.none { r -> base.any { it.id == r.id } } && base.size + routes.size <= RouteCodec.MAX_ROUTES)
        val written = prefs.edit().putString("rutas_importadas_json", RouteCodec.encode(routes))
            .putInt("siguiente_id", maxOf(nextId(), (routes.maxOfOrNull { it.id } ?: 5000) + 1)).commit()
        if (!written) {
            prefs.edit().putString("rutas_importadas_json", raw).commit()
            error("No se pudo escribir la copia; los cambios siguen bloqueados.")
        }
        blocked = false
        notice = "Se restauró la copia anterior. El original dañado queda protegido."
        return routes.size
    }
    fun nextId(): Int = (prefs.all["siguiente_id"] as? Int ?: 5001).coerceAtLeast(5001)
    // Both backup and primary write must be confirmed before the UI reports success.
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun save(routes: List<Ruta>, removedIds: Set<Int> = emptySet()) {
        check(!blocked) { notice }
        require(routes.size <= RouteCodec.MAX_ROUTES && routes.all { it.id in 1 until RouteCodec.MAX_ID } &&
            routes.map { it.id }.distinct().size == routes.size) { "Rutas o identificadores inválidos." }
        val text = RouteCodec.encode(routes)
        RouteCodec.decode(text, true) // includes size and field bounds
        val previous = prefs.getString("rutas_importadas_json", "[]") ?: "[]"
        check(recovery.edit().putString("anterior", previous).commit()) { "No se pudo proteger la copia anterior." }
        val editor = prefs.edit().putString("rutas_importadas_json", text)
            .putInt("siguiente_id", maxOf(nextId(), (routes.maxOfOrNull { it.id } ?: 5000) + 1))
        for (key in listOf("favoritos_rutas", "rutas_realizadas")) {
            val set = (prefs.all[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: mutableSetOf()
            set.removeAll(removedIds.map { it.toString() }.toSet())
            editor.putStringSet(key, set)
        }
        if (!editor.commit()) {
            blocked = true
            prefs.edit().putString("rutas_importadas_json", previous).commit()
            notice = "No se pudo guardar. Exporta los datos y revisa el almacenamiento antes de continuar."
            throw IllegalStateException(notice)
        }
    }
}
