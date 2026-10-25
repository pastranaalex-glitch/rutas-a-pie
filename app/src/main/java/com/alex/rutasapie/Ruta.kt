package com.alex.rutasapie

data class Ruta(
        val id: Int,
        val provincia: String,
        val nombre: String,
        val ubicacion: String,
        val comoLlegar: String,
        val distancia: String,
        val desnivel: String,
        val duracion: String,
        val dificultad: String,
        val tipo: String,
        val categoria: String,
        val descripcion: String,
        val consejos: List<String>,
        val importada: Boolean = false,
        val comunidad: String = "",
        val fuente: String = ""
    )

