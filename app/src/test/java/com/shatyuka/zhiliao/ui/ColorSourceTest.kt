package com.shatyuka.zhiliao.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSourceTest {
    @Test
    fun wallpaperColorsStartAtAndroid12() {
        assertEquals(ColorSource.Fallback, colorSource(sdkInt = 30, followWallpaper = true))
        assertEquals(ColorSource.Dynamic, colorSource(sdkInt = 31, followWallpaper = true))
        assertEquals(ColorSource.Dynamic, colorSource(sdkInt = 36, followWallpaper = true))
    }

    @Test
    fun turningOffWallpaperUsesTheFixedScheme() {
        assertEquals(ColorSource.Fallback, colorSource(sdkInt = 34, followWallpaper = false))
        assertEquals(ColorSource.Fallback, colorSource(sdkInt = 26, followWallpaper = false))
    }
}
