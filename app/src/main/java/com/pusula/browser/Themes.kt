package com.pusula.browser

/** Uygulama renk temaları. */
class Palette(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bg: Int,
    val surface: Int,
    val field: Int,
    val text: Int,
    val sub: Int,
    val icon: Int,
    val accent: Int,
    val accent2: Int
)

object Themes {
    private fun c(v: Long): Int = v.toInt()

    val all: List<Palette> = listOf(
        Palette("gece", "Gece Mavisi", true,
            c(0xFF0B1020), c(0xFF131B33), c(0xFF1D2848), c(0xFFEAF0FF), c(0xFF8FA0C8), c(0xFFC9D6FF), c(0xFF4C8DFF), c(0xFF8A5CFF)),
        Palette("mor", "Mor Gece", true,
            c(0xFF120B1F), c(0xFF1A1230), c(0xFF2A1F4A), c(0xFFF1EAFF), c(0xFFA99BCF), c(0xFFD8C8FF), c(0xFF9B6BFF), c(0xFFFF5CA8)),
        Palette("orman", "Orman", true,
            c(0xFF08140F), c(0xFF0E1F17), c(0xFF173126), c(0xFFE6F7EE), c(0xFF86B8A0), c(0xFFBFE8D3), c(0xFF2ED47A), c(0xFF1BC5C5)),
        Palette("gunbatimi", "Gün Batımı", true,
            c(0xFF1A0F0F), c(0xFF241414), c(0xFF3A2020), c(0xFFFFEDE6), c(0xFFC9988A), c(0xFFFFD2C2), c(0xFFFF7A45), c(0xFFFF3D77)),
        Palette("acik", "Açık", false,
            c(0xFFF2F4F9), c(0xFFFFFFFF), c(0xFFE8ECF5), c(0xFF151A26), c(0xFF667089), c(0xFF3A4560), c(0xFF3D6BFF), c(0xFF7A5CFF)),
        Palette("okyanus", "Okyanus", false,
            c(0xFFEAF6FA), c(0xFFFFFFFF), c(0xFFDDEFF5), c(0xFF0F2530), c(0xFF5A7886), c(0xFF2A4E5E), c(0xFF0EA5C6), c(0xFF2F6BFF))
    )

    fun get(id: String): Palette = all.firstOrNull { it.id == id } ?: all[0]
}
