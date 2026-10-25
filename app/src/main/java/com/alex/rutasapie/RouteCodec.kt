package com.alex.rutasapie

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

object RouteCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ROUTES = 1000
    const val MAX_ID = 1_000_000_000
    fun read(stream: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            require(out.size() + n <= MAX_BYTES) { "El archivo supera 2 MB." }
            out.write(buffer, 0, n)
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
    }
    fun decode(text: String, imported: Boolean): List<Ruta> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "El archivo supera 2 MB." }
        val clean = text.trim().removePrefix("\uFEFF").trim()
        require(clean.isNotBlank()) { "El archivo está vacío." }
        val array = if (clean.startsWith("[")) JSONArray(clean) else {
            val obj = JSONObject(clean)
            if (obj.has("version")) require(obj.get("version") is Number && (obj.get("version") as Number).toDouble() == 1.0) { "Versión de archivo no compatible." }
            when {
                obj.has("rutas") -> obj.getJSONArray("rutas")
                obj.has("items") -> obj.getJSONArray("items")
                else -> JSONArray().put(obj)
            }
        }
        require(array.length() <= MAX_ROUTES) { "Máximo 1000 rutas por archivo." }
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun field(key: String, max: Int = 1000, fallback: String = ""): String {
                if (!o.has(key)) return fallback
                val value = o.get(key)
                require(value is String && value.length <= max) { "Ruta ${i+1}: campo $key inválido." }
                require(value.none { it == '\u0000' }) { "Ruta ${i+1}: texto inválido." }
                return value.trim()
            }
            val name = field("nombre", 200)
            require(name.isNotBlank()) { "Ruta ${i+1}: falta el nombre." }
            val idValue = o.opt("id")
            val id = if (idValue == null) 0 else {
                require(idValue is Number && idValue.toDouble() % 1.0 == 0.0 &&
                    idValue.toDouble() in 0.0..MAX_ID.toDouble()) { "Ruta ${i+1}: ID inválido." }
                idValue.toInt()
            }
            val tips = when (val v = o.opt("consejos")) {
                null -> emptyList()
                is String -> v.split('|', '\n', ';').map { it.trim() }.filter { it.isNotBlank() }
                is JSONArray -> (0 until v.length()).map { j ->
                    val value = v.get(j)
                    require(value is String) { "Ruta ${i+1}: consejo inválido." }
                    value.trim()
                }.filter { it.isNotBlank() }
                else -> throw IllegalArgumentException("Ruta ${i+1}: consejos inválidos.")
            }
            require(tips.size <= 100 && tips.all { it.length <= 2000 }) { "Ruta ${i+1}: demasiados consejos o muy largos." }
            val source = field("fuente", 2000)
            require(validSource(source)) { "Ruta ${i+1}: la fuente debe ser una dirección HTTPS válida." }
            Ruta(id, field("provincia", 100, "Sin zona").ifBlank { "Sin zona" }, name,
                field("ubicacion"), if (o.has("comoLlegar")) field("comoLlegar", 4000) else field("como_llegar", 4000),
                field("distancia", 200), field("desnivel", 200), field("duracion", 200),
                field("dificultad", 200), field("tipo", 200), field("categoria", 200),
                field("descripcion", 20000), tips, imported, field("comunidad", 100), source)
        }
    }
    fun json(r: Ruta): JSONObject = JSONObject().apply {
        put("id", r.id); put("provincia", r.provincia); put("nombre", r.nombre)
        put("ubicacion", r.ubicacion); put("comoLlegar", r.comoLlegar); put("distancia", r.distancia)
        put("desnivel", r.desnivel); put("duracion", r.duracion); put("dificultad", r.dificultad)
        put("tipo", r.tipo); put("categoria", r.categoria); put("descripcion", r.descripcion)
        put("consejos", JSONArray(r.consejos))
        if (r.comunidad.isNotBlank()) put("comunidad", r.comunidad)
        if (r.fuente.isNotBlank()) put("fuente", r.fuente)
    }
    fun validSource(value: String): Boolean = value.isEmpty() || runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
    fun encode(routes: List<Ruta>): String = JSONArray().apply { routes.forEach { put(json(it)) } }.toString(2)
    // Only exact copies are omitted. Changed variants are preserved as additional routes.
    fun uniqueIncoming(existing: List<Ruta>, incoming: List<Ruta>): List<Ruta> {
        fun key(r: Ruta): Ruta = r.copy(id = 0, importada = false)
        val seen = existing.map(::key).toMutableSet()
        return incoming.filter { seen.add(key(it)) }
    }
    fun assignIds(existing: List<Ruta>, incoming: List<Ruta>, next: Int): List<Ruta> {
        require(existing.size + incoming.size <= MAX_ROUTES) { "El catálogo no puede superar 1000 rutas." }
        var id = maxOf(5001, next, (existing.maxOfOrNull { it.id } ?: 0) + 1)
        return incoming.map {
            require(id < MAX_ID) { "No quedan identificadores disponibles." }
            it.copy(id = id++, importada = true)
        }
    }
}
