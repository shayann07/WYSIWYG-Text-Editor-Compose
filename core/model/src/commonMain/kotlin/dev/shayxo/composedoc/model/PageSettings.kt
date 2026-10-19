package dev.shayxo.composedoc.model

private const val MIN_MARGIN_MM = 5
private const val MAX_MARGIN_MM = 40
private const val A4_WIDTH_MM = 210f
private const val A4_HEIGHT_MM = 297f
private const val LETTER_WIDTH_MM = 215.9f
private const val LETTER_HEIGHT_MM = 279.4f

enum class PaperSize { A4, LETTER }

data class PageSettings(val paper: PaperSize = PaperSize.A4, val landscape: Boolean = false, val marginMm: Int = 14) {
    init {
        require(marginMm in MIN_MARGIN_MM..MAX_MARGIN_MM) { "Margins must be 5–40 mm" }
    }

    val widthMm get() = if (landscape)heightPortrait else widthPortrait
    val heightMm get() = if (landscape)widthPortrait else heightPortrait
    private val widthPortrait get() = if (paper == PaperSize.A4)A4_WIDTH_MM else LETTER_WIDTH_MM
    private val heightPortrait get() = if (paper == PaperSize.A4)A4_HEIGHT_MM else LETTER_HEIGHT_MM
}
