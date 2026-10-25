package com.alex.rutasapie

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : ComponentActivity() {

    private lateinit var txtResumen: TextView
    private lateinit var edtBuscar: EditText
    private lateinit var spinnerProvincia: Spinner
    private lateinit var contenedorRutas: RecyclerView
    private lateinit var tarjetas: CardsAdapter
    private var detalleId: Int? = null
    private var errorCatalogo: String? = null
    private var formulario: AlertDialog? = null
    private var camposFormulario: List<EditText> = emptyList()
    private var borrador: List<String>? = null
    private var busquedaPendiente: Job? = null
    private val store by lazy { RouteStore(this) }

    private val rutasBase = mutableListOf<Ruta>()
    private val rutasImportadas = mutableListOf<Ruta>()
    private val todasRutas = mutableListOf<Ruta>()

    private var provinciaActual: String = "Todas"
    private var ordenActual: String = "Nombre"
    private var filtroMarcas = "Todas"

    private val prefs by lazy {
        getSharedPreferences("rutas_a_pie_prefs", Context.MODE_PRIVATE)
    }

    private val importarArchivo = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importarRutasDesdeUri(it) }
    }
    private val exportarArchivo = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { exportarRutasAUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        provinciaActual = savedInstanceState?.getString("zona") ?: "Todas"
        ordenActual = savedInstanceState?.getString("orden") ?: "Nombre"
        filtroMarcas = savedInstanceState?.getString("marcas") ?: "Todas"
        cargarTodasLasRutas()
        val root = crearPantallaPrincipal()
        setContentView(root)
        val left = root.paddingLeft; val top = root.paddingTop; val right = root.paddingRight; val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
        androidx.core.view.WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        ViewCompat.requestApplyInsets(root)
        configurarSelectorProvincia()
        edtBuscar.setText(savedInstanceState?.getString("busqueda").orEmpty())
        actualizarListaRutas()
        savedInstanceState?.let { androidx.core.os.BundleCompat.getParcelable(it, "lista", android.os.Parcelable::class.java) }?.let {
            contenedorRutas.layoutManager?.onRestoreInstanceState(it)
        }
        savedInstanceState?.getInt("detalle", -1)?.takeIf { it > 0 }?.let { id ->
            todasRutas.find { it.id == id }?.let { mostrarDetalleRuta(it) }
        }
        savedInstanceState?.getStringArrayList("borrador")?.let { borrador = it; mostrarFormularioAnadirRuta() }
        if (store.notice.isNotBlank()) AlertDialog.Builder(this).setTitle("Recuperación de datos")
            .setMessage(store.notice).setPositiveButton("Entendido", null).show()
    }

    private fun crearPantallaPrincipal(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(238, 245, 238))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }

        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titulo = TextView(this).apply {
            setText(R.string.catalog_title)
            textSize = 26f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(24, 69, 45))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnAjustes = ImageButton(this).apply {
            contentDescription = "Ajustes"
            setImageResource(android.R.drawable.ic_menu_manage)
            background = fondoRedondeado(Color.WHITE, dp(18), Color.rgb(190, 210, 190), dp(1))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            setOnClickListener {
                mostrarMenuAjustes()
            }
        }

        cabecera.addView(titulo)
        cabecera.addView(btnAjustes)
        raiz.addView(cabecera)

        val subtitulo = TextView(this).apply {
            setText(R.string.catalog_subtitle)
            textSize = 14f
            setTextColor(Color.rgb(70, 95, 78))
            setPadding(0, dp(2), 0, dp(10))
        }
        raiz.addView(subtitulo)

        val filaAccesosScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val filaAccesos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        filaAccesos.addView(crearBotonSuperior("🧭 Mapa y GPS") { abrirGps() })
        filaAccesos.addView(crearBotonSuperior(getString(R.string.help_action)) { HelpActivity.open(this) }.apply { id = R.id.catalog_help })
        filaAccesos.addView(crearBotonSuperior("★ Mis rutas") { mostrarFiltroMarcas() })
        filaAccesos.addView(crearBotonSuperior("🚨 Llamada rápida") { llamarEmergencia() })
        filaAccesos.addView(crearBotonSuperior("🆘 Ayuda senderista") { mostrarAyudaSenderista() })
        filaAccesos.addView(crearBotonSuperior("🌦️ AEMET") { abrirAemet() })

        filaAccesosScroll.addView(filaAccesos)
        raiz.addView(filaAccesosScroll)

        edtBuscar = EditText(this).apply {
            hint = "🔍 Buscar ruta, provincia, comunidad..."
            contentDescription = "Buscar rutas"
            textSize = 15f
            setSingleLine(true)
            setTextColor(Color.rgb(35, 45, 35))
            setHintTextColor(Color.rgb(110, 130, 110))
            background = fondoRedondeado(Color.WHITE, dp(16), Color.rgb(195, 215, 195), dp(1))
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                setMargins(0, dp(12), 0, dp(8))
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    busquedaPendiente?.cancel()
                    busquedaPendiente = lifecycleScope.launch { delay(150); actualizarListaRutas() }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        raiz.addView(edtBuscar)

        val filaFiltros = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        spinnerProvincia = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                setMargins(0, 0, dp(8), 0)
            }
            background = fondoRedondeado(Color.WHITE, dp(14), Color.rgb(195, 215, 195), dp(1))
        }

        val btnOrdenar = Button(this).apply {
            setText(R.string.catalog_sort_button)
            textSize = 13f
            setTextColor(Color.WHITE)
            background = fondoRedondeado(Color.rgb(39, 112, 72), dp(14))
            layoutParams = LinearLayout.LayoutParams(dp(135), dp(50))
            setOnClickListener {
                mostrarMenuOrdenar()
            }
        }

        filaFiltros.addView(spinnerProvincia)
        filaFiltros.addView(btnOrdenar)
        raiz.addView(filaFiltros)

        txtResumen = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(70, 95, 78))
            setPadding(dp(2), dp(8), dp(2), dp(6))
        }
        raiz.addView(txtResumen)

        contenedorRutas = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
        }
        tarjetas = CardsAdapter()
        contenedorRutas.adapter = tarjetas
        raiz.addView(contenedorRutas)

        return raiz
    }

    private fun abrirGps(ruta: Ruta? = null) {
        startActivity(Intent(this, GpsActivity::class.java).apply { ruta?.let { putExtra("route_name", it.nombre) } })
    }

    private fun mostrarFiltroMarcas() {
        val opciones=arrayOf("Todas", "Favoritas", "Realizadas", "Pendientes")
        AlertDialog.Builder(this).setTitle("Mis rutas")
            .setSingleChoiceItems(opciones, opciones.indexOf(filtroMarcas).coerceAtLeast(0)) { dialog, index ->
                filtroMarcas=opciones[index]; dialog.dismiss(); actualizarListaRutas()
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun crearBotonSuperior(texto: String, accion: () -> Unit): Button {
        return Button(this).apply {
            text = texto
            textSize = 13f
            setTextColor(Color.WHITE)
            background = fondoRedondeado(Color.rgb(39, 112, 72), dp(16))
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48)
            ).apply {
                setMargins(0, 0, dp(8), 0)
            }
            setOnClickListener { accion() }
        }
    }

    private fun configurarSelectorProvincia() {
        val provincias = RouteGeography.filters(todasRutas)

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, provincias) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply {
                    (this as? TextView)?.setTextColor(Color.rgb(24, 69, 45))
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).apply {
                    setBackgroundColor(Color.WHITE)
                    (this as? TextView)?.setTextColor(Color.rgb(24, 69, 45))
                    minimumHeight = dp(48)
                }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        if (provinciaActual !in provincias) provinciaActual = "Todas"
        spinnerProvincia.contentDescription = "Comunidad, provincia o zona del catálogo"
        spinnerProvincia.onItemSelectedListener = null
        spinnerProvincia.adapter = adapter
        spinnerProvincia.setSelection(provincias.indexOf(provinciaActual))
        spinnerProvincia.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                provinciaActual = provincias.getOrElse(position) { "Todas" }
                actualizarListaRutas()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun actualizarListaRutas() {
        if (!::contenedorRutas.isInitialized || !::edtBuscar.isInitialized) return

        val query = edtBuscar.text?.toString().orEmpty()
        val favoritos = obtenerSet("favoritos_rutas")
        val realizadas = obtenerSet("rutas_realizadas")
        val filtradas = todasRutas.filter { RouteLogic.matches(it, query, provinciaActual) && when(filtroMarcas) {
            "Favoritas" -> it.id.toString() in favoritos
            "Realizadas" -> it.id.toString() in realizadas
            "Pendientes" -> it.id.toString() !in realizadas
            else -> true
        } }
            .sortedWith(comparadorOrdenActual())
        val advertencia = when {
            errorCatalogo != null -> getString(R.string.catalog_warning, errorCatalogo)
            store.blocked -> getString(R.string.catalog_warning, getString(R.string.catalog_protected))
            else -> ""
        }
        txtResumen.text = resources.getQuantityString(R.plurals.catalog_summary, filtradas.size, filtradas.size, filtroMarcas, ordenActual,
            advertencia, if(filtradas.isEmpty()) getString(R.string.catalog_empty) else "")
        tarjetas.actualizar(filtradas)
    }

    private inner class CardsAdapter : RecyclerView.Adapter<CardsAdapter.Holder>() {
        private var items = emptyList<Ruta>()
        private var favorites = emptySet<String>()
        private var completed = emptySet<String>()
        init { setHasStableIds(true) }
        inner class Holder(val frame: android.widget.FrameLayout) : RecyclerView.ViewHolder(frame)
        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].id.toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(android.widget.FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.frame.removeAllViews(); holder.frame.addView(crearTarjetaRuta(items[position]))
        }
        fun actualizar(list: List<Ruta>) {
            val old = items
            val newFavorites = obtenerSet("favoritos_rutas").toSet()
            val newCompleted = obtenerSet("rutas_realizadas").toSet()
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(a: Int, b: Int) = old[a].id == list[b].id
                override fun areContentsTheSame(a: Int, b: Int): Boolean {
                    val id = old[a].id.toString()
                    return old[a] == list[b] && (id in favorites) == (id in newFavorites) &&
                        (id in completed) == (id in newCompleted)
                }
            })
            items = list; favorites = newFavorites; completed = newCompleted
            diff.dispatchUpdatesTo(this)
        }
    }

    private fun comparadorOrdenActual(): Comparator<Ruta> {
        return when (ordenActual) {
            "Distancia" -> compareBy { extraerNumero(it.distancia) }
            "Duración" -> compareBy { extraerMinutos(it.duracion) }
            "Dificultad" -> compareBy { valorDificultad(it.dificultad) }
            "Comunidad / zona" -> compareBy<Ruta> { normalizar(RouteGeography.community(it).ifBlank { it.provincia }) }
                .thenBy { normalizar(it.provincia) }.thenBy { normalizar(it.nombre) }
            "Provincia" -> compareBy<Ruta> { normalizar(it.provincia) }.thenBy { normalizar(it.nombre) }
            "Categoría" -> compareBy<Ruta> { normalizar(it.categoria) }.thenBy { normalizar(it.nombre) }
            else -> compareBy { normalizar(it.nombre) }
        }
    }

    private fun crearTarjetaRuta(ruta: Ruta): View {
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(Color.WHITE, dp(18), Color.rgb(205, 220, 205), dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(6), 0, dp(8))
            }
        }

        val filaTitulo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titulo = TextView(this).apply {
            text = ruta.nombre
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(24, 69, 45))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val etiqueta = TextView(this).apply {
            text = if (ruta.importada) "Importada" else ruta.provincia
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = fondoRedondeado(Color.rgb(39, 112, 72), dp(12))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        filaTitulo.addView(titulo)
        filaTitulo.addView(etiqueta)
        tarjeta.addView(filaTitulo)

        val ubicacion = TextView(this).apply {
            text = getString(R.string.route_location, ruta.ubicacion.ifBlank { ruta.provincia })
            textSize = 13f
            setTextColor(Color.rgb(72, 92, 72))
            setPadding(0, dp(4), 0, dp(8))
        }
        tarjeta.addView(ubicacion)

        val datos = TextView(this).apply {
            val faltante=getString(R.string.missing_data)
            text=getString(R.string.route_stats, ruta.distancia.ifBlank {faltante}, ruta.desnivel.ifBlank {faltante},
                ruta.duracion.ifBlank {faltante}, ruta.dificultad.ifBlank {faltante}, ruta.tipo.ifBlank {faltante},
                ruta.categoria.ifBlank {getString(R.string.natural_category)})
            textSize = 13f
            setTextColor(Color.rgb(45, 60, 45))
            setPadding(0, 0, 0, dp(8))
        }
        tarjeta.addView(datos)

        val descripcionCorta = TextView(this).apply {
            text = ruta.descripcion.ifBlank { "Ruta de senderismo para consultar antes de salir." }
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 14f
            setTextColor(Color.rgb(55, 70, 55))
            maxLines = 3
            setPadding(0, 0, 0, dp(10))
        }
        tarjeta.addView(descripcionCorta)

        val filaBotones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        filaBotones.addView(crearBotonTarjeta("Detalles") {
            mostrarDetalleRuta(ruta)
        })

        filaBotones.addView(crearBotonTarjeta("GPS") {
            abrirGps(ruta)
        })

        filaBotones.addView(crearBotonTarjeta("Maps") {
            abrirRutaEnMaps(ruta)
        })

        filaBotones.addView(crearBotonTarjeta(if (esFavorita(ruta.id)) "★" else "☆") {
            alternarFavorita(ruta)
            actualizarListaRutas()
        })

        filaBotones.addView(crearBotonTarjeta(if (estaRealizada(ruta.id)) "✓ Hecha" else "Marcar") {
            alternarRealizada(ruta)
            actualizarListaRutas()
        })

        tarjeta.addView(filaBotones)

        return tarjeta
    }

    private fun crearBotonTarjeta(texto: String, accion: () -> Unit): Button {
        return Button(this).apply {
            text = texto
            isAllCaps = false
            contentDescription = when (texto) { "★" -> "Quitar de favoritas"; "☆" -> "Añadir a favoritas"; else -> texto }
            textSize = 12f
            setTextColor(Color.WHITE)
            background = fondoRedondeado(Color.rgb(30, 94, 58), dp(12))
            minimumHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
            setOnClickListener { accion() }
        }
    }

    private fun mostrarDetalleRuta(ruta: Ruta) {
        detalleId = ruta.id
        val consejosTexto = if (ruta.consejos.isEmpty()) {
            "• Consulta la previsión meteorológica antes de salir.\n• Lleva agua, calzado adecuado y batería suficiente."
        } else {
            ruta.consejos.joinToString("\n") { "• $it" }
        }

        val mensaje = """
            Datos orientativos. Confirma el itinerario y su estado antes de salir.

            📍 Zona del catálogo: ${ruta.provincia}
            🗺️ Comunidad: ${RouteGeography.community(ruta).ifBlank { "Sin dato" }}
            📌 Ubicación: ${ruta.ubicacion}

            📏 Distancia: ${ruta.distancia.ifBlank { "Sin dato" }}
            ⛰️ Desnivel: ${ruta.desnivel.ifBlank { "Sin dato" }}
            ⏱️ Duración: ${ruta.duracion.ifBlank { "Sin dato" }}
            🟢 Dificultad: ${ruta.dificultad.ifBlank { "Sin dato" }}
            🔁 Tipo: ${ruta.tipo.ifBlank { "Sin dato" }}
            🏷️ Categoría: ${ruta.categoria.ifBlank { "Natural" }}

            🚗 Cómo llegar:
            ${ruta.comoLlegar.ifBlank { "Consulta la ubicación en Maps antes de salir." }}

            📝 Descripción:
            ${ruta.descripcion.ifBlank { "Ruta de senderismo recomendada para preparar con antelación." }}

            ✅ Consejos:
            $consejosTexto
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(ruta.nombre)
            .setMessage(mensaje)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Button(this@MainActivity).apply {
                    text = if(esFavorita(ruta.id)) "Quitar de favoritas" else "Añadir a favoritas"
                    isAllCaps=false
                    setOnClickListener {
                        alternarFavorita(ruta)
                        text=if(esFavorita(ruta.id)) "Quitar de favoritas" else "Añadir a favoritas"
                        actualizarListaRutas()
                    }
                })
                if (ruta.fuente.isNotBlank() && RouteCodec.validSource(ruta.fuente)) addView(Button(this@MainActivity).apply {
                    setText(R.string.route_source_action)
                    isAllCaps = false
                    setOnClickListener {
                        try { startActivity(Intent(Intent.ACTION_VIEW, ruta.fuente.toUri())) }
                        catch (_: Exception) { mostrarError(getString(R.string.browser_failed)) }
                    }
                })
            })
            .setPositiveButton("Mapa y GPS") { _, _ -> abrirGps(ruta) }
            .setNeutralButton("Cómo llegar") { _, _ -> abrirRutaEnMaps(ruta) }
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener { detalleId = null }
            .show()
    }

    private fun mostrarMenuAjustes() {
        val opciones = arrayOf(
            "Acerca / Legal / Privacidad",
            "Gestión de rutas",
            "Importar rutas",
            "Exportar rutas",
            "Añadir ruta",
            "Borrar rutas importadas",
            "Cambiar número de emergencia",
            "Información senderista",
            "España Activa",
            "GR / PR / SL",
            "Sistema MIDE",
            "Recuperar copia anterior",
            getString(R.string.help_action)
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ Ajustes")
            .setItems(opciones) { _, which ->
                when (which) {
                    0 -> mostrarAcercaLegalPrivacidad()
                    1 -> mostrarGestionRutas()
                    2 -> seleccionarArchivoImportar()
                    3 -> seleccionarArchivoExportar()
                    4 -> mostrarFormularioAnadirRuta()
                    5 -> confirmarBorrarRutasImportadas()
                    6 -> cambiarNumeroEmergencia()
                    7 -> mostrarInformacionSenderista()
                    8 -> mostrarEspanaActiva()
                    9 -> mostrarSenalizacion()
                    10 -> mostrarSistemaMide()
                    11 -> recuperarCopiaAnterior()
                    12 -> HelpActivity.open(this)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun recuperarCopiaAnterior() {
        if (!store.blocked) { mostrarError("Los datos guardados no están bloqueados. No hace falta recuperarlos."); return }
        AlertDialog.Builder(this).setTitle("Recuperar copia anterior")
            .setMessage("Se sustituirán los datos importados dañados por la última copia anterior disponible. El original dañado se conservará por separado. Las rutas base, favoritos y realizadas se mantienen. Después, revisa y exporta tus rutas.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Restaurar") { _, _ ->
                try { val n=store.restorePrevious(rutasBase); cargarTodasLasRutas(); configurarSelectorProvincia(); actualizarListaRutas(); mostrarError("Copia restaurada: $n rutas importadas.") }
                catch (e: Exception) { mostrarError("No se ha restaurado la copia: ${e.message}") }
            }.show()
    }

    private fun mostrarMenuOrdenar() {
        val opciones = arrayOf(
            "Nombre",
            "Distancia",
            "Duración",
            "Dificultad",
            "Comunidad / zona",
            "Provincia",
            "Categoría"
        )

        AlertDialog.Builder(this)
            .setTitle("Ordenar rutas")
            .setSingleChoiceItems(opciones, opciones.indexOf(ordenActual).coerceAtLeast(0)) { dialog, which ->
                ordenActual = opciones[which]
                dialog.dismiss()
                actualizarListaRutas()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun cargarTodasLasRutas() {
        rutasBase.clear()
        rutasImportadas.clear()
        todasRutas.clear()

        rutasBase.addAll(cargarRutasDesdeAssets())
        rutasImportadas.addAll(cargarRutasImportadas())

        todasRutas.addAll(rutasBase)
        todasRutas.addAll(rutasImportadas)
    }

    private fun cargarRutasDesdeAssets(): List<Ruta> {
        return try {
            val texto = assets.open("rutas.json").bufferedReader().use { it.readText() }
            parsearRutasDesdeTexto(texto, importada = false).also { items ->
                require(items.isNotEmpty() && items.all { it.id > 0 } && items.map { it.id }.distinct().size == items.size) { "Catálogo base inválido." }
                errorCatalogo = null
            }
        } catch (e: Exception) {
            errorCatalogo = "No se pudo leer el catálogo base: ${e.message}. Se bloquean cambios de rutas."
            Toast.makeText(
                this,
                errorCatalogo,
                Toast.LENGTH_LONG
            ).show()
            emptyList()
        }
    }

    private fun cargarRutasImportadas(): List<Ruta> = store.load(rutasBase)

    private fun guardarRutasImportadas(nuevas: List<Ruta> = rutasImportadas, removed: Set<Int> = emptySet()) {
        store.save(nuevas, removed)
    }
    private fun parsearRutasDesdeTexto(texto: String, importada: Boolean): List<Ruta> = RouteCodec.decode(texto, importada)

    private fun seleccionarArchivoImportar() {
        try { importarArchivo.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        catch (_: Exception) { mostrarError("No se pudo abrir el selector de archivos.") }
    }
    private fun seleccionarArchivoExportar() {
        try { exportarArchivo.launch("rutas_exportadas_rutas_a_pie.json") }
        catch (_: Exception) { mostrarError("No se pudo crear el archivo de exportación.") }
    }

    private fun importarRutasDesdeUri(uri: Uri) {
        if (store.blocked || errorCatalogo != null) { mostrarError(errorCatalogo ?: store.notice); return }
        lifecycleScope.launch {
            try {
                val nuevas = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo.")
                    stream.use { RouteCodec.decode(RouteCodec.read(it), true) }
                }
                val distintas = RouteCodec.uniqueIncoming(todasRutas, nuevas)
                if (distintas.isEmpty()) { mostrarError("No hay rutas nuevas: las copias exactas ya existen."); return@launch }
                AlertDialog.Builder(this@MainActivity).setTitle("Confirmar importación")
                    .setMessage("Se añadirán ${distintas.size} rutas; se omiten ${nuevas.size-distintas.size} copias exactas. Los datos actuales se conservan. Las variantes modificadas se añaden como rutas nuevas.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Añadir") { _, _ ->
                        try {
                            val actual = RouteCodec.uniqueIncoming(todasRutas, distintas)
                            val prepared = RouteCodec.assignIds(todasRutas, actual, store.nextId())
                            guardarRutasImportadas(rutasImportadas + prepared)
                            cargarTodasLasRutas(); configurarSelectorProvincia(); actualizarListaRutas()
                            Toast.makeText(this@MainActivity, "Añadidas ${prepared.size} rutas", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) { mostrarError(e.message ?: "No se pudo importar.") }
                    }.show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mostrarError("No se importó ningún dato: ${e.message}") }
        }
    }

    private fun exportarRutasAUri(uri: Uri) {
        val snapshot = todasRutas.toList()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val encoded = RouteCodec.encode(snapshot)
                    RouteCodec.decode(encoded, true)
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("No se pudo abrir el destino.")
                    output.use { it.write(encoded.toByteArray(Charsets.UTF_8)); it.flush() }
                }
                Toast.makeText(this@MainActivity, "${snapshot.size} rutas exportadas correctamente", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mostrarError("Exportación incompleta: ${e.message}. Revisa el archivo antes de usarlo.") }
        }
    }
    private fun mostrarError(message: String) {
        AlertDialog.Builder(this).setTitle("Rutas a Pie").setMessage(message).setPositiveButton("Entendido", null).show()
    }

    private fun mostrarFormularioAnadirRuta() {
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val edtProvincia = crearCampo("Comunidad / zona", "")
        val edtNombre = crearCampo("Nombre de la ruta", "")
        val edtUbicacion = crearCampo("Ubicación", "")
        val edtComoLlegar = crearCampo("Cómo llegar", "")
        val edtDistancia = crearCampo("Distancia", "8 km")
        val edtDesnivel = crearCampo("Desnivel", "250 m")
        val edtDuracion = crearCampo("Duración", "2 h 30 min")
        val edtDificultad = crearCampo("Dificultad", "Fácil / Media / Alta")
        val edtTipo = crearCampo("Tipo", "Circular / Lineal / Ida y vuelta")
        val edtCategoria = crearCampo("Categoría", "Montaña / Costa / Río / Familiar")
        val edtDescripcion = crearCampo("Descripción", "")
        val edtConsejos = crearCampo("Consejos separados por punto y coma", "")

        listOf(
            edtProvincia,
            edtNombre,
            edtUbicacion,
            edtComoLlegar,
            edtDistancia,
            edtDesnivel,
            edtDuracion,
            edtDificultad,
            edtTipo,
            edtCategoria,
            edtDescripcion,
            edtConsejos
        ).also { campos ->
            camposFormulario = campos
            borrador?.let { saved -> campos.forEachIndexed { i, campo -> saved.getOrNull(i)?.let { campo.setText(it) } } }
            borrador = null
        }.forEach { contenedor.addView(it) }

        val scroll = ScrollView(this).apply {
            addView(contenedor)
        }

        if (store.blocked || errorCatalogo != null) { mostrarError(errorCatalogo ?: store.notice); return }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Añadir ruta")
            .setView(scroll)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nombre = edtNombre.text.toString().trim()
                if (nombre.isBlank()) {
                    Toast.makeText(this, "La ruta necesita un nombre", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val nuevoId = maxOf(store.nextId(), ((todasRutas.maxOfOrNull { it.id } ?: 5000) + 1).coerceAtLeast(5001))

                val consejos = edtConsejos.text.toString()
                    .split(";", "\n", "|")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val ruta = Ruta(
                    id = nuevoId,
                    provincia = edtProvincia.text.toString().trim().ifBlank { "Sin provincia" },
                    nombre = nombre,
                    ubicacion = edtUbicacion.text.toString().trim(),
                    comoLlegar = edtComoLlegar.text.toString().trim(),
                    distancia = edtDistancia.text.toString().trim(),
                    desnivel = edtDesnivel.text.toString().trim(),
                    duracion = edtDuracion.text.toString().trim(),
                    dificultad = edtDificultad.text.toString().trim(),
                    tipo = edtTipo.text.toString().trim(),
                    categoria = edtCategoria.text.toString().trim(),
                    descripcion = edtDescripcion.text.toString().trim(),
                    consejos = consejos,
                    importada = true
                )

                try {
                    require(todasRutas.size < RouteCodec.MAX_ROUTES) { "Máximo 1000 rutas." }
                    guardarRutasImportadas(rutasImportadas + ruta)
                    cargarTodasLasRutas(); configurarSelectorProvincia(); actualizarListaRutas()
                    dialog.dismiss()
                    Toast.makeText(this, "Ruta añadida", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { mostrarError(e.message ?: "No se pudo guardar.") }
            }
        }
        formulario = dialog
        dialog.setOnDismissListener { formulario = null; camposFormulario = emptyList() }
        dialog.show()
    }

    private fun crearCampo(hint: String, texto: String): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(texto)
            textSize = 14f
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(Color.rgb(35, 45, 35))
            setHintTextColor(Color.rgb(115, 130, 115))
            background = fondoRedondeado(Color.WHITE, dp(12), Color.rgb(200, 215, 200), dp(1))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(5), 0, dp(7))
            }
        }
    }

    private fun confirmarBorrarRutasImportadas() {
        if (rutasImportadas.isEmpty()) {
            Toast.makeText(this, "No hay rutas importadas para borrar", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Borrar rutas importadas")
            .setMessage("Se borrarán solo las rutas añadidas o importadas. Las rutas de assets/rutas.json no se tocarán.")
            .setPositiveButton("Borrar") { _, _ ->
                try {
                    guardarRutasImportadas(emptyList(), rutasImportadas.map { it.id }.toSet())
                    cargarTodasLasRutas(); configurarSelectorProvincia(); actualizarListaRutas()
                    Toast.makeText(this, "Rutas importadas borradas", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { mostrarError(e.message ?: "No se pudo borrar.") }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarGestionRutas() {
        val favoritas = obtenerSet("favoritos_rutas").size
        val realizadas = obtenerSet("rutas_realizadas").size

        val mensaje = """
            ${store.notice}
            Rutas totales: ${todasRutas.size}
            Rutas del archivo base: ${rutasBase.size}
            Rutas importadas/añadidas: ${rutasImportadas.size}

            Favoritas: $favoritas
            Realizadas: $realizadas

            Desde aquí puedes importar, exportar, añadir o borrar rutas importadas desde el menú de Ajustes.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Gestión de rutas")
            .setMessage(mensaje)
            .setPositiveButton("Importar") { _, _ -> seleccionarArchivoImportar() }
            .setNeutralButton("Exportar") { _, _ -> seleccionarArchivoExportar() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun mostrarAcercaLegalPrivacidad() {
        val mensaje = """
            Rutas a Pie: Senderismo Fácil

            Versión ${BuildConfig.VERSION_NAME} · ${rutasBase.size} rutas, mapa integrado y grabación GPS.
            Aplicación para organizar rutas y registrar caminatas.

            Aviso importante:
            La información de rutas puede cambiar por meteorología, obras, cierres, desprendimientos, mareas o normas locales. Antes de salir, confirma siempre el estado de la ruta en fuentes oficiales.

            Privacidad:
            Los favoritos, rutas realizadas, número de emergencia, rutas importadas y recorridos GPS se guardan localmente.
            La cartografía de OpenStreetMap necesita internet y recibe tu IP y las teselas del área que consultas.
            Los recorridos GPS no se envían a un servidor. Compartir y exportar solo ocurren cuando lo solicitas.
            Las preferencias pueden participar en la copia de Android; los recorridos se excluyen de la copia en la nube.
            Mapa: MapLibre Native 13.6.1 (licencia BSD) y datos © OpenStreetMap contributors (ODbL).
            Las fichas del catálogo no contienen trazados GPS verificados. Importa un GPX para consultar su geometría. Las nuevas fichas enlazan a su fuente oficial.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Acerca / Legal / Privacidad")
            .setMessage(mensaje)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun mostrarAyudaSenderista() {
        val mensaje = """
            Consejos rápidos:

            • Consulta AEMET antes de salir.
            • Lleva agua, algo de comida, calzado adecuado y móvil con batería.
            • En montaña o costa, evita salir con niebla fuerte, temporal o mala visibilidad.
            • En rutas con acantilados, no te acerques al borde.
            • Si vas solo, avisa a alguien de la ruta prevista.
            • En emergencia, llama al 112.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("🆘 Ayuda senderista")
            .setMessage(mensaje)
            .setPositiveButton("Llamar 112") { _, _ ->
                abrirTelefono("112")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun mostrarInformacionSenderista() {
        val mensaje = """
            Información básica para preparar una ruta:

            • Fácil: rutas cortas, poco desnivel o recorrido sencillo.
            • Media: más distancia, desnivel moderado o terreno irregular.
            • Alta: rutas largas, montaña, desnivel importante o mayor exigencia.

            Revisa siempre:
            • Meteorología.
            • Horas de luz.
            • Estado del camino.
            • Agua disponible.
            • Cobertura móvil.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Información senderista")
            .setMessage(mensaje)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun mostrarEspanaActiva() {
        val mensaje = """
            España Activa

            Idea para futuras versiones:
            añadir información útil para moverse, caminar y descubrir rutas por provincias, con datos claros y filtros sencillos.

            En esta versión queda como apartado informativo dentro de Ajustes.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("España Activa")
            .setMessage(mensaje)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun mostrarSenalizacion() {
        val mensaje = """
            Señalización senderista:

            GR: Sendero de Gran Recorrido.
            Normalmente se marca en blanco y rojo.

            PR: Sendero de Pequeño Recorrido.
            Normalmente se marca en blanco y amarillo.

            SL: Sendero Local.
            Normalmente se marca en blanco y verde.

            En futuras versiones se pueden añadir estos campos directamente a cada ruta.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("GR / PR / SL")
            .setMessage(mensaje)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun mostrarSistemaMide() {
        val mensaje = """
            Sistema MIDE

            El MIDE ayuda a valorar una ruta según:

            • Medio natural.
            • Orientación.
            • Desplazamiento.
            • Esfuerzo.

            En futuras versiones podemos añadir estos 4 campos a cada ruta para mejorar la seguridad y la información.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Sistema MIDE")
            .setMessage(mensaje)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    // The return value is checked to avoid reporting success after a failed write.
    @SuppressLint("UseKtx")
    private fun cambiarNumeroEmergencia() {
        val actual = prefs.all["telefono_emergencia"] as? String ?: "112"

        val campo = EditText(this).apply {
            setText(actual)
            hint = "Número de emergencia"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        AlertDialog.Builder(this)
            .setTitle("Cambiar número de emergencia")
            .setMessage("Puedes dejar 112 o poner otro número rápido.")
            .setView(campo)
            .setPositiveButton("Guardar") { _, _ ->
                val numero = campo.text.toString().trim().ifBlank { "112" }
                if (!numero.matches("\\+?[0-9 ()-]{2,30}".toRegex())) {
                    mostrarError("Escribe un número de teléfono válido, sin códigos especiales.")
                } else if (prefs.edit().putString("telefono_emergencia", numero).commit()) {
                    Toast.makeText(this, "Número guardado: $numero", Toast.LENGTH_SHORT).show()
                } else mostrarError("No se pudo guardar el número.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun llamarEmergencia() {
        val numero = prefs.all["telefono_emergencia"] as? String ?: "112"
        abrirTelefono(numero)
    }

    private fun abrirTelefono(numero: String) {
        if (!numero.matches("\\+?[0-9 ()-]{2,30}".toRegex())) {
            mostrarError("El número guardado no es válido. Revisa Ajustes; el teléfono de emergencia es 112.")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", numero, null))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el teléfono", Toast.LENGTH_SHORT).show()
        }
    }

    private fun abrirAemet() {
        abrirWeb("https://www.aemet.es/es/eltiempo/prediccion/municipios")
    }

    private fun abrirWeb(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
        }
    }

    private fun abrirRutaEnMaps(ruta: Ruta) {
        // Prose in "Cómo llegar" is not a geocodable destination.
        val consulta = listOf(ruta.nombre, ruta.ubicacion).filter { it.isNotBlank() }.joinToString(", ")

        val uri = "geo:0,0?q=${Uri.encode(consulta)}".toUri()
        val intentMaps = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            startActivity(intentMaps)
        } catch (_: android.content.ActivityNotFoundException) {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            catch (_: Exception) { mostrarError("No hay una aplicación de mapas compatible. El catálogo sigue disponible.") }
        } catch (_: Exception) { mostrarError("No se pudo abrir la aplicación de mapas.") }
    }

    private fun esFavorita(id: Int): Boolean {
        return obtenerSet("favoritos_rutas").contains(id.toString())
    }

    private fun estaRealizada(id: Int): Boolean {
        return obtenerSet("rutas_realizadas").contains(id.toString())
    }

    private fun alternarFavorita(ruta: Ruta) {
        if (!alternarEnSet("favoritos_rutas", ruta.id)) return
        Toast.makeText(
            this,
            if (esFavorita(ruta.id)) "Añadida a favoritas" else "Quitada de favoritas",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun alternarRealizada(ruta: Ruta) {
        if (!alternarEnSet("rutas_realizadas", ruta.id)) return
        Toast.makeText(
            this,
            if (estaRealizada(ruta.id)) "Marcada como realizada" else "Quitada de realizadas",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun obtenerSet(clave: String): MutableSet<String> {
        return (prefs.all[clave] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: mutableSetOf()
    }

    // Checked synchronous commit is intentional: a failed write must restore the previous marks.
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun alternarEnSet(clave: String, id: Int): Boolean {
        val previous = obtenerSet(clave)
        val set = previous.toMutableSet()
        val valor = id.toString()

        if (set.contains(valor)) {
            set.remove(valor)
        } else {
            set.add(valor)
        }

        if (!prefs.edit().putStringSet(clave, set).commit()) {
            prefs.edit().putStringSet(clave, previous).commit()
            mostrarError("No se pudo guardar la marca. Revisa el almacenamiento.")
            return false
        }
        return true
    }

    private fun fondoRedondeado(
        color: Int,
        radio: Int,
        bordeColor: Int? = null,
        bordeAncho: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radio.toFloat()
            setColor(color)
            if (bordeColor != null && bordeAncho > 0) {
                setStroke(bordeAncho, bordeColor)
            }
        }
    }

    private fun dp(valor: Int): Int {
        return (valor * resources.displayMetrics.density).toInt()
    }

    private fun normalizar(texto: String) = RouteLogic.normalize(texto)
    private fun extraerNumero(texto: String) = RouteLogic.distance(texto)
    private fun extraerMinutos(texto: String) = RouteLogic.minutes(texto)
    private fun valorDificultad(texto: String) = RouteLogic.difficulty(texto)

    override fun onDestroy() {
        formulario?.dismiss()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("zona", provinciaActual); outState.putString("orden", ordenActual)
        outState.putString("marcas", filtroMarcas)
        outState.putString("busqueda", edtBuscar.text?.toString().orEmpty())
        outState.putInt("detalle", detalleId ?: -1)
        if (formulario?.isShowing == true) outState.putStringArrayList("borrador", ArrayList(camposFormulario.map { it.text.toString() }))
        outState.putParcelable("lista", contenedorRutas.layoutManager?.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }
}
