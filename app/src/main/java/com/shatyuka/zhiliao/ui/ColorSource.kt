package com.shatyuka.zhiliao.ui

/** Where the settings screen gets its colors. */
enum class ColorSource {
    /** Android 12+ wallpaper colors (Material You). */
    Dynamic,

    /** Fixed scheme used below Android 12, and when dynamic color is turned off. */
    Fallback,
}

/**
 * Android 12 (API 31) is the first release that exposes wallpaper colors to apps.
 * Turning off "跟随系统壁纸" keeps the fixed scheme even on newer systems.
 */
fun colorSource(sdkInt: Int, followWallpaper: Boolean): ColorSource =
    if (sdkInt >= 31 && followWallpaper) ColorSource.Dynamic else ColorSource.Fallback
