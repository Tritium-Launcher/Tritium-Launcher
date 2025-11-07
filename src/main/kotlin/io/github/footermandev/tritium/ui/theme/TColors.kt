package io.github.footermandev.tritium.ui.theme

object TColors {

    val Surface0 = color("Surface0")
    val Surface1 = color("Surface1")
    val Surface2 = color("Surface2")
    val Surface3 = color("Surface3")
    val Text     = color("Text")
    val Subtext  = color("Subtext")
    val Accent   = color("Accent")


    fun color(key: String): String = ThemeMngr.getColorHex(key) ?: "#ffffff"
}