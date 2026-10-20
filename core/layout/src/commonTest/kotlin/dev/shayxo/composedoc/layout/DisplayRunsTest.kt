package dev.shayxo.composedoc.layout

import dev.shayxo.composedoc.model.DisplayStyle
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayRunsTest {
    @Test fun joiningWordsAndSupplementaryClustersHaveNoArtificialStyleSeams() {
        for (text in listOf("کتاب", "العَرَبِيَّة", "👩🏽‍💻", "e\u0301")) {
            val document = Document(text.mapIndexed { i, c -> TextUnit(i.toLong() + 1, c) })
            assertEquals(listOf(DisplayStyle(0, text.length, TextStyleFlags())), renderedStyleRuns(document))
            assertEquals(text, document.text)
        }
    }

    @Test fun hardBreakAndRealAttributeChangesStaySeparate() {
        val text = "ab\ncd"
        val document = Document(
            text.mapIndexed { i, c ->
                TextUnit(
                    i.toLong() + 1,
                    c,
                    if (c == '\n')UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                    if (i == 0)TextStyleFlags(bold = true) else TextStyleFlags(),
                )
            },
        )
        assertEquals(
            listOf(
                DisplayStyle(0, 1, TextStyleFlags(bold = true)),
                DisplayStyle(1, 2, TextStyleFlags()),
                DisplayStyle(3, 5, TextStyleFlags()),
            ),
            renderedStyleRuns(document),
        )
    }

    @Test fun projectionNeverRewritesRawInteriorClusterAttributes() {
        val doc = Document(listOf(TextUnit(1, 'e'), TextUnit(2, '\u0301', style = TextStyleFlags(bold = true))))
        assertEquals(listOf(DisplayStyle(0, 2, TextStyleFlags())), renderedStyleRuns(doc))
        assertTrue(doc.units[1].style.bold)
    }
}
