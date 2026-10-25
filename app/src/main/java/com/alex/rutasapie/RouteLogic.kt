package com.alex.rutasapie

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

object RouteLogic {
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "").trim()

    // For a range, sort by its lower bound, never by the last value before the unit.
    private val number = "\\d+(?:[.,]\\d+)?".toRegex()
    fun distance(text: String): Double {
        val n = number.find(text)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return Double.MAX_VALUE
        return if ("\\b(?:m|metros)\\b".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            !"\\b(?:km|kilometros|kilómetros)\\b".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(text)) n / 1000 else n
    }
    fun minutes(text: String): Int {
        val normalized = normalize(text)
        val split = normalized.indexOfFirst { it == '-' || it == '–' }
        val left = if (split >= 0) normalized.substring(0, split) else ""
        val t = if ("\\b(?:h|horas?|min|minutos?)\\b".toRegex().containsMatchIn(left)) left else normalized
        val hours = "(${number.pattern})(?:\\s*[-–]\\s*${number.pattern})?\\s*(?:h|horas?)\\b".toRegex().find(t)
        val mins = "(${number.pattern})(?:\\s*[-–]\\s*${number.pattern})?\\s*(?:min|minutos?)\\b".toRegex().find(t)
        if (hours == null && mins == null) return Int.MAX_VALUE // no guessed units
        val total = (hours?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0) * 60 +
            (mins?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0)
        return if (total > 0 && total < Int.MAX_VALUE) total.roundToInt() else Int.MAX_VALUE
    }
    fun difficulty(text: String): Int {
        val t = normalize(text)
        return when {
            t.contains("alta") -> 3
            t.contains("media") -> 2
            t.contains("facil") || t.contains("baja") -> 1
            else -> 99
        }
    }
    fun matches(route: Ruta, query: String, region: String): Boolean =
        (region == "Todas" || normalize(route.provincia) == normalize(region) ||
            normalize(RouteGeography.community(route)) == normalize(region)) &&
        normalize(listOf(route.nombre, route.provincia, RouteGeography.community(route), route.ubicacion, route.dificultad,
            route.tipo, route.categoria, route.descripcion).joinToString(" ")).contains(normalize(query))
}
