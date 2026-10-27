package com.pusula.browser

/**
 * Uygulama renk temaları.
 * accent3: üçüncü vurgu rengi (karşılama sayfasındaki ışık/manzara katmanları ve başlık geçişi).
 * wall: karşılama sayfasının duvar kâğıdı stili (orbs, stars, aurora, hills, dunes, sunset, waves, grid).
 */
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
    val accent2: Int,
    val accent3: Int = accent2,
    val wall: String = "orbs"
)

object Themes {
    private fun c(v: Long): Int = v.toInt()

    val all: List<Palette> = listOf(
        // ---------- Koyu temalar ----------
        Palette("gece", "Gece Mavisi", true,
            c(0xFF0A0F1F), c(0xFF121A33), c(0xFF1C2748), c(0xFFEAF0FF), c(0xFF8E9CC4), c(0xFFC9D6FF),
            c(0xFF5B9DFF), c(0xFF8F6BFF), c(0xFF36D1FF), "stars"),
        Palette("amoled", "Amoled", true,
            c(0xFF000000), c(0xFF0D0D12), c(0xFF1A1A22), c(0xFFF2F2F7), c(0xFF8A8A99), c(0xFFD0D0DC),
            c(0xFF3EA6FF), c(0xFF7C5CFF), c(0xFF00E0C6), "orbs"),
        Palette("mor", "Mor Gece", true,
            c(0xFF100A1E), c(0xFF1A1230), c(0xFF2A1F4A), c(0xFFF1EAFF), c(0xFFA99BCF), c(0xFFD8C8FF),
            c(0xFF9B6BFF), c(0xFFFF5CA8), c(0xFF5C7CFF), "aurora"),
        Palette("aurora", "Aurora", true,
            c(0xFF07131A), c(0xFF0D2029), c(0xFF15343F), c(0xFFE4FBF6), c(0xFF7FB5B0), c(0xFFBDEFE6),
            c(0xFF2DE2B0), c(0xFF6C7BFF), c(0xFFB26BFF), "aurora"),
        Palette("orman", "Orman", true,
            c(0xFF08140F), c(0xFF0E1F17), c(0xFF173126), c(0xFFE6F7EE), c(0xFF86B8A0), c(0xFFBFE8D3),
            c(0xFF2ED47A), c(0xFF1BC5C5), c(0xFF9BE15D), "hills"),
        Palette("gunbatimi", "Gün Batımı", true,
            c(0xFF1A0F12), c(0xFF26151A), c(0xFF3B2029), c(0xFFFFEDE6), c(0xFFC9988A), c(0xFFFFD2C2),
            c(0xFFFF7A45), c(0xFFFF3D77), c(0xFFFFB84D), "sunset"),
        Palette("gul", "Gül", true,
            c(0xFF1A0D14), c(0xFF261320), c(0xFF3B1F31), c(0xFFFFEAF3), c(0xFFC795AE), c(0xFFFFC9E0),
            c(0xFFFF4F9A), c(0xFFFF8A5C), c(0xFFC04DFF), "orbs"),
        Palette("grafit", "Grafit", true,
            c(0xFF111315), c(0xFF1A1D21), c(0xFF262A30), c(0xFFE9ECEF), c(0xFF8D959E), c(0xFFC5CBD3),
            c(0xFF4FD1C5), c(0xFF63A4FF), c(0xFFF6AD55), "grid"),

        // ---------- Açık temalar ----------
        Palette("acik", "Açık", false,
            c(0xFFF3F5FA), c(0xFFFFFFFF), c(0xFFE7EBF5), c(0xFF151A26), c(0xFF667089), c(0xFF3A4560),
            c(0xFF3D6BFF), c(0xFF7A5CFF), c(0xFF22B8F0), "orbs"),
        Palette("okyanus", "Okyanus", false,
            c(0xFFEAF6FA), c(0xFFFFFFFF), c(0xFFD9EDF4), c(0xFF0F2530), c(0xFF5A7886), c(0xFF2A4E5E),
            c(0xFF0EA5C6), c(0xFF2F6BFF), c(0xFF22D3B8), "waves"),
        Palette("lavanta", "Lavanta", false,
            c(0xFFF5F1FC), c(0xFFFFFFFF), c(0xFFECE4F8), c(0xFF211A33), c(0xFF746A94), c(0xFF4B3F72),
            c(0xFF7C4DFF), c(0xFFE04CC0), c(0xFF4D8DFF), "aurora"),
        Palette("kum", "Kum", false,
            c(0xFFF7F1E8), c(0xFFFFFDF8), c(0xFFEFE5D6), c(0xFF2A2118), c(0xFF8A7861), c(0xFF5B4A36),
            c(0xFFD9702B), c(0xFFC2415D), c(0xFFE8A33D), "dunes"),
        Palette("nane", "Nane", false,
            c(0xFFEEF7F2), c(0xFFFFFFFF), c(0xFFDFEEE5), c(0xFF12261C), c(0xFF5E7F6D), c(0xFF2F5642),
            c(0xFF14A36B), c(0xFF1595B8), c(0xFF6CCB5F), "hills")
    )

    fun get(id: String): Palette = all.firstOrNull { it.id == id } ?: all[0]
}
