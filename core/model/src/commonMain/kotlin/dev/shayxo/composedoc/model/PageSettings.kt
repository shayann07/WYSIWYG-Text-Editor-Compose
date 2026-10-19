package dev.shayxo.composedoc.model

enum class PaperSize { A4, LETTER }

data class PageSettings(val paper: PaperSize = PaperSize.A4, val landscape: Boolean = false, val marginMm: Int = 14) {
    init {
        require(marginMm in 5..40) { "Margins must be 5–40 mm" }
    }

    val widthMm get() = if (landscape)heightPortrait else widthPortrait
    val heightMm get() = if (landscape)widthPortrait else heightPortrait
    private val widthPortrait get() = if (paper == PaperSize.A4)210f else 215.9f
    private val heightPortrait get() = if (paper == PaperSize.A4)297f else 279.4f
}
