package com.alex.rutasapie

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HelpActivityTest {
    @Test fun catalogueActionOpensOfflineGuide() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        c.get().findViewById<View>(R.id.catalog_help).performClick()
        assertEquals(HelpActivity::class.java.name,shadowOf(c.get()).nextStartedActivity.component?.className)
        c.pause().stop().destroy()
    }
    @Test fun gpsHelpOpensRequestedSectionAndBackReturns() {
        val intent=Intent(RuntimeEnvironment.getApplication(),HelpActivity::class.java).putExtra("section","gps")
        val c=Robolectric.buildActivity(HelpActivity::class.java,intent).setup();val a=c.get()
        val root=a.findViewById<LinearLayout>(R.id.help_sections)
        assertNotNull(root.findViewWithTag<View>("body_gps"));assertNull(root.findViewWithTag<View>("body_empezar"))
        a.findViewById<View>(R.id.help_back).performClick();assertTrue(a.isFinishing)
        c.pause().stop().destroy()
    }
    @Test fun searchHandlesAccentsAndEmptyResults() {
        val c=Robolectric.buildActivity(HelpActivity::class.java).setup();val a=c.get()
        val search=a.findViewById<EditText>(R.id.help_search);val root=a.findViewById<LinearLayout>(R.id.help_sections)
        search.setText("precision");assertNotNull(root.findViewWithTag<View>("body_precision"))
        search.setText("zzzsincoincidenciaszzz");assertEquals(1,root.childCount)
        search.setText("");assertNotNull(root.findViewWithTag<View>("body_empezar"))
        c.pause().stop().destroy()
    }
    @Test fun queryAndCollapsedSectionSurviveRecreation() {
        val c=Robolectric.buildActivity(HelpActivity::class.java).setup();val a=c.get()
        a.findViewById<EditText>(R.id.help_search).setText("precision")
        a.findViewById<LinearLayout>(R.id.help_sections).findViewWithTag<View>("precision").performClick()
        val state=Bundle();c.saveInstanceState(state).pause().stop().destroy()
        val c2=Robolectric.buildActivity(HelpActivity::class.java).create(state).start().resume();val a2=c2.get()
        assertEquals("precision",a2.findViewById<EditText>(R.id.help_search).text.toString())
        assertNull(a2.findViewById<LinearLayout>(R.id.help_sections).findViewWithTag<View>("body_precision"))
        c2.pause().stop().destroy()
    }
}
