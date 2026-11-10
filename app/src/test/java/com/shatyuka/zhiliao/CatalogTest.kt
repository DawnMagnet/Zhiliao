package com.shatyuka.zhiliao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preference keys are the on-disk contract. Renaming one would reset that switch for everyone. */
class CatalogTest {
    @Test
    fun preferenceKeysStayStable() {
        val keys = (Catalog.switchItems.map { it.key } + Catalog.textItems.map { it.key }).toSet()
        assertEquals(EXPECTED_KEYS, keys)
    }

    @Test
    fun adSwitchesDefaultOnAndFiltersDefaultOff() {
        val byKey = Catalog.switchItems.associate { it.key to it.defaultValue }
        assertTrue(byKey.getValue("switch_mainswitch"))
        assertTrue(byKey.getValue("switch_dynamic_color"))
        assertTrue(byKey.getValue("switch_launchad"))
        assertTrue(byKey.getValue("switch_feedad"))
        assertFalse(byKey.getValue("switch_video"))
        assertFalse(byKey.getValue("switch_autorefresh"))
        assertFalse(byKey.getValue("switch_antitracking"))
        assertTrue(Catalog.textItems.any { it.key == "edit_title" })
    }

    companion object {
        private val EXPECTED_KEYS = setOf(
            "switch_mainswitch",
            "switch_dynamic_color",
            "switch_launchad",
            "switch_feedad",
            "switch_feedfloatad",
            "switch_answerlistad",
            "switch_commentad",
            "switch_sharead",
            "switch_answerad",
            "switch_searchad",
            "switch_video",
            "switch_removearticle",
            "switch_pin",
            "switch_marketcard",
            "switch_club",
            "switch_goods",
            "switch_related",
            "switch_searchwords",
            "switch_externlink",
            "switch_externlinkex",
            "switch_colormode",
            "switch_tag",
            "switch_statusbar",
            "switch_fullscreen",
            "switch_thirdpartylogin",
            "switch_autorefresh",
            "switch_antitracking",
            "switch_antitracking_link",
            "switch_antitracking_qr",
            "switch_reddot",
            "switch_vipbanner",
            "switch_hotbanner",
            "switch_article",
            "switch_feedtophot",
            "switch_minehybrid",
            "switch_homenav",
            "switch_findnav",
            "switch_panelnav",
            "switch_messagenav",
            "switch_profilenav",
            "switch_navres",
            "switch_nextanswer",
            "switch_webview_debug",
            "switch_watermark",
            "switch_imagesource",
            "switch_subscribe",
            "edit_js",
            "switch_autoclean",
            "switch_silenceclean",
            "edit_title",
            "edit_author",
            "edit_content",
            "switch_hidetoast",
            "switch_hooktrace",
        )
    }
}
