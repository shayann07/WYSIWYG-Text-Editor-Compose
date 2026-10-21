package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListColumnsTest {
    @Test
    fun oppositeDirectionChildKeepsAncestralIndentAndItsOwnMarkerGutter() {
        // Independent reference drawing: 240px column, 30px right root gutter.
        // Child text reserves 60px at the right and 16px for its own LTR marker at the left.
        val blocks = listOf(block(0, "root"), block(1, "child", "root"))
        val directions = listOf(ParagraphDirection.RTL, ParagraphDirection.LTR)
        val columns = SharedListColumns(
            blocks,
            directions,
            240f,
            mapOf(":true" to 30f, "root:true" to 16f),
            mapOf(":true" to 20f, "root:true" to 20f),
            40f,
        )
        assertEquals(SharedListColumn(0f, 210f, true, 30f), columns.column("root"))
        assertEquals(SharedListColumn(16f, 164f, true, 16f), columns.column("child"))
        assertEquals(ParagraphDirection.LTR, directions[1], "Nesting side must never overwrite text/marker direction")
    }

    @Test
    fun ltrRootAndRtlChildHaveMirroredColumnsWithSharedSiblingGutters() {
        val blocks = listOf(block(0, "root"), block(1, "a", "root"), block(2, "b", "root"))
        val columns = SharedListColumns(
            blocks,
            listOf(ParagraphDirection.LTR, ParagraphDirection.RTL, ParagraphDirection.LTR),
            240f,
            mapOf(":false" to 30f, "root:false" to 47f),
            mapOf(":false" to 20f, "root:false" to 20f),
            40f,
        )
        // The 47px sibling marker fixes both children's common column, regardless of one/many digits.
        assertEquals(SharedListColumn(77f, 116f, false, 47f), columns.column("a"))
        assertEquals(columns.column("a"), columns.column("b"))
    }

    @Test
    fun continuationParagraphsDoNotCreateNewAncestorsOrChangeSiblingColumns() {
        val blocks = listOf(block(0, "root"), block(1, "root"), block(2, "child", "root"))
        val columns = SharedListColumns(
            blocks,
            listOf(ParagraphDirection.RTL, ParagraphDirection.RTL, ParagraphDirection.LTR),
            240f,
            mapOf(":true" to 30f, "root:true" to 16f),
            mapOf(":true" to 20f, "root:true" to 20f),
            40f,
        )
        assertEquals(SharedListColumn(16f, 164f, true, 16f), columns.column("child"))
        assertTrue(columns.column("") == null)
    }

    private fun block(index: Int, item: String, parent: String = "") = ParagraphBlock(
        index,
        index * 4,
        index * 4 + 3,
        index.toLong(),
        ParagraphProperties(item = item, parent = parent, list = ListKind.BULLET),
        true,
    )
}
