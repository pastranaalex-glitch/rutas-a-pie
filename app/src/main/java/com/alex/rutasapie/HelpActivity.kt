package com.alex.rutasapie

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import org.json.JSONArray

data class HelpSection(val id: String, val title: String, val body: String)

object HelpRepository {
    fun load(context: Context): List<HelpSection> {
        val array = JSONArray(context.assets.open("ayuda.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val sections = (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            HelpSection(obj.getString("id"), obj.getString("titulo"), obj.getString("texto"))
        }
        require(sections.isNotEmpty() && sections.map { it.id }.distinct().size == sections.size &&
            sections.all { it.id.isNotBlank() && it.title.isNotBlank() && it.body.isNotBlank() })
        return sections
    }
}

/** Plain native views: the guide is local and never needs a network or location permission. */
class HelpActivity : ComponentActivity() {
    private var sections = emptyList<HelpSection>()
    private val expanded = mutableSetOf<String>()
    private lateinit var search: EditText
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var initializing = true
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sections = try { HelpRepository.load(this) } catch (_: Exception) { loadFailed = true; emptyList() }
        val requested = intent.getStringExtra("section")?.takeIf { id -> sections.any { it.id == id } }
        expanded.addAll(savedInstanceState?.getStringArrayList("expanded") ?: listOf(requested ?: "empezar"))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(238, 245, 238))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply {
            id = R.id.help_back; setText(R.string.help_back); isAllCaps = false
            minimumHeight = dp(48); setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            setText(R.string.help_title); textSize = 21f
            setTextColor(Color.rgb(24, 69, 45)); setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        root.addView(TextView(this).apply { setText(R.string.help_intro); textSize = 15f; setTextColor(Color.rgb(45, 65, 45)); setPadding(0, dp(6), 0, dp(8)) })
        search = EditText(this).apply {
            id = R.id.help_search; setHint(R.string.help_search_hint)
            contentDescription = getString(R.string.help_search_hint)
            textSize = 16f; setSingleLine(true); minimumHeight = dp(48)
            isSaveEnabled = false // Query and expanded sections are restored together below.
            setTextColor(Color.rgb(24, 45, 24)); setHintTextColor(Color.rgb(75, 95, 75))
            setText(savedInstanceState?.getString("query").orEmpty())
        }
        root.addView(search)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply { id = R.id.help_sections; orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        ViewCompat.requestApplyInsets(root)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (initializing) return
                expanded.clear()
                if (s.isNullOrBlank()) expanded.add("empezar") else expanded.addAll(filtered().map { it.id })
                render()
                scroll.scrollTo(0, 0)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        initializing = false
        render()
        content.doOnLayout {
            if (savedInstanceState != null) scroll.scrollTo(0, savedInstanceState.getInt("scroll"))
            else requested?.let { id -> content.findViewWithTag<View>(id)?.let { scroll.scrollTo(0, it.top) } }
        }
    }

    private fun filtered(): List<HelpSection> {
        val query = RouteLogic.normalize(search.text.toString())
        return sections.filter { RouteLogic.normalize(it.title + " " + it.body).contains(query) }
    }
    private fun render() {
        val shown = filtered()
        content.removeAllViews()
        if (shown.isEmpty()) content.addView(TextView(this).apply {
            setText(if (loadFailed) R.string.help_load_failed else R.string.help_empty)
            textSize = 16f; setTextColor(Color.rgb(45, 65, 45)); setPadding(dp(8), dp(12), dp(8), dp(12))
        })
        shown.forEach { section ->
            val opened = section.id in expanded
            content.addView(Button(this).apply {
                tag = section.id; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = getString(if (opened) R.string.help_section_open else R.string.help_section_closed, section.title)
                contentDescription = getString(if (opened) R.string.help_collapse else R.string.help_expand, section.title)
                textSize = 16f; minimumHeight = dp(48)
                setTextColor(Color.rgb(24, 69, 45))
                setOnClickListener {
                    val position = scroll.scrollY
                    if (!expanded.add(section.id)) expanded.remove(section.id)
                    render()
                    scroll.post { scroll.scrollTo(0, position) }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (opened) content.addView(TextView(this).apply {
                tag = "body_" + section.id
                text = section.body; textSize = 16f; setTextColor(Color.rgb(35, 50, 35))
                setPadding(dp(10), dp(8), dp(10), dp(16)); setLineSpacing(dp(3).toFloat(), 1f)
                setTextIsSelectable(true)
            })
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", search.text.toString())
        outState.putStringArrayList("expanded", ArrayList(expanded))
        outState.putInt("scroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        fun open(context: Context, section: String? = null) {
            context.startActivity(Intent(context, HelpActivity::class.java).apply { section?.let { putExtra("section", it) } })
        }
    }
}
