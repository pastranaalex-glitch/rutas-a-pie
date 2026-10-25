package com.alex.rutasapie

/** Older catalogues used provincia for whole communities. Keep those values and IDs. */
object RouteGeography {
    val communities = listOf("Andalucía", "Aragón", "Asturias", "Islas Baleares", "Canarias",
        "Cantabria", "Castilla y León", "Castilla-La Mancha", "Cataluña", "Comunidad Valenciana",
        "Extremadura", "Galicia", "Madrid", "Región de Murcia", "Navarra", "País Vasco", "La Rioja")
    private val provinceGroups = mapOf(
        "Andalucía" to listOf("Almería", "Cádiz", "Córdoba", "Granada", "Huelva", "Jaén", "Málaga", "Sevilla"),
        "Aragón" to listOf("Huesca", "Teruel", "Zaragoza"),
        "Asturias" to listOf("Asturias"), "Islas Baleares" to listOf("Illes Balears", "Baleares", "Islas Baleares"),
        "Canarias" to listOf("Las Palmas", "Santa Cruz de Tenerife"), "Cantabria" to listOf("Cantabria"),
        "Castilla y León" to listOf("Ávila", "Burgos", "León", "Palencia", "Salamanca", "Segovia", "Soria", "Valladolid", "Zamora"),
        "Castilla-La Mancha" to listOf("Albacete", "Ciudad Real", "Cuenca", "Guadalajara", "Toledo"),
        "Cataluña" to listOf("Barcelona", "Girona", "Lleida", "Tarragona"),
        "Comunidad Valenciana" to listOf("Alicante", "Castellón", "Valencia"),
        "Extremadura" to listOf("Badajoz", "Cáceres"), "Galicia" to listOf("A Coruña", "Lugo", "Ourense", "Pontevedra"),
        "Madrid" to listOf("Madrid"), "Región de Murcia" to listOf("Murcia"), "Navarra" to listOf("Navarra"),
        "País Vasco" to listOf("Álava", "Bizkaia", "Gipuzkoa", "Vizcaya", "Guipúzcoa"), "La Rioja" to listOf("La Rioja")
    )
    private val byProvince = provinceGroups.flatMap { (community, provinces) ->
        provinces.map { RouteLogic.normalize(it) to community }
    }.toMap()
    fun community(route: Ruta): String = route.comunidad.ifBlank {
        communities.find { RouteLogic.normalize(it) == RouteLogic.normalize(route.provincia) }
            ?: byProvince[RouteLogic.normalize(route.provincia)].orEmpty()
    }
    fun filters(routes: List<Ruta>): List<String> = listOf("Todas") +
        routes.flatMap { listOf(it.provincia.trim(), community(it)) }.filter { it.isNotBlank() }.distinct()
            .sortedBy(RouteLogic::normalize)
}
