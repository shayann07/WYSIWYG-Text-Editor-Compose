@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shayxo.composedoc.ui

import androidx.compose.runtime.*
import androidx.compose.ui.text.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import javax.accessibility.*
import javax.swing.*
import kotlin.math.*

class ViewState(frame: EditorFrame) {
    var paged by mutableStateOf(false)
        private set
    fun setPagedMode(value: Boolean) {
        paged = value
        invalidateGeometry()
    }
    var typography by mutableStateOf(TypographyProfile.system)
        private set
    fun changeTypography(profile: TypographyProfile) {
        typography = profile
        invalidateGeometry()
    }
    private fun invalidateGeometry() {
        geometry = null
        measure = null
        measureAtSize = null
        singleMeasure = null
        referenceMeasure = null
        ensureCaret = true
    }
    var zoom by mutableStateOf(1f)
        private set
    fun changeZoom(value: Float) {
        require(value in .5f..1f)
        zoom = value
        invalidateGeometry()
    }
    var frame by mutableStateOf(frame)
    var scroll by mutableStateOf(0f)
    var focused by mutableStateOf(false)
    var blink by mutableStateOf(true)
    var ensureCaret = true
    var findDocument by mutableStateOf<Document?>(null, referentialEqualityPolicy())
    var findMatches by mutableStateOf(emptyList<FindMatch>())
    var findActive by mutableStateOf(-1)
    var findReveal by mutableStateOf<Selection?>(null)
    var hostWidth: Int = 0
    var hostHeight: Int = 0
    var geometry: Geometry? = null
    var layoutError: String? = null
    var measure: ((EditorFrame) -> Geometry)? = null
    var measureAtSize: ((EditorFrame, Float, Float) -> Geometry)? = null
    var singleMeasure: ((Document) -> TextLayoutResult)? = null
    var referenceMeasure: ((Document) -> List<Pair<ParagraphSpan, TextLayoutResult>>)? = null
}
