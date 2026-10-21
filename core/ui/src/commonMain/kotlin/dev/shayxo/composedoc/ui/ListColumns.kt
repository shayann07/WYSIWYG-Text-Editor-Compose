package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphDirection

internal data class SharedListColumn(val left: Float, val width: Float, val rootRtl: Boolean, val gutter: Float)

/** Same root-side nesting and widest-sibling gutter policy on JVM and Android. Measurements are platform inputs. */
internal class SharedListColumns(
    blocks: List<ParagraphBlock>,
    private val directions: List<ParagraphDirection>,
    private val textWidth: Float,
    private val widths: Map<String, Float>,
    private val ems: Map<String, Float>,
    private val minimumTextWidth: Float,
) {
    private val items = blocks.filter { it.properties.item.isNotEmpty() }.distinctBy { it.properties.item }
    private val byId = items.associateBy { it.properties.item }
    private val ancestry = items.associate { it.properties.item to chain(it.properties.item) }
    private val maximumDepth = ancestry.values.maxOfOrNull { it.size } ?: 1
    private val opposite = items.filter {
        (directions[it.index] == ParagraphDirection.RTL) !=
            rootRtl(it.properties.item)
    }
        .map { group(it.properties.item) }.toSet()

    private fun chain(start: String): List<String> {
        val result = ArrayList<String>()
        var item = start
        while (item.isNotEmpty()) {
            require(item !in result && result.size < MAX_DEPTH) { "Invalid list ancestry" }
            result += item
            item = byId.getValue(item).properties.parent
        }
        return result
    }
    private fun rootRtl(item: String) =
        directions[byId.getValue(ancestry.getValue(item).last()).index] == ParagraphDirection.RTL
    fun group(item: String) = "${byId.getValue(item).properties.parent}:${rootRtl(item)}"
    fun column(item: String): SharedListColumn? {
        if (item.isEmpty()) return null
        val chain = ancestry.getValue(item)
        val gutter = chain.maxOf { widths.getValue(group(it)) }
        val nominalStep = chain.maxOf { ems.getValue(group(it)) * STEP_EM }
        val step = minOf(nominalStep, ((textWidth - gutter - minimumTextWidth) / maximumDepth).coerceAtLeast(0f))
        val indent = maxOf(gutter, step) + step * (chain.size - 1)
        val root = rootRtl(item)
        val oppositeGutter = if (group(item) in opposite) widths.getValue(group(item)) else 0f
        val available = (textWidth - indent - oppositeGutter).coerceAtLeast(1f)
        val left = if (root) textWidth - indent - available else indent
        return SharedListColumn(left, available, root, widths.getValue(group(item)))
    }
    companion object {
        private const val MAX_DEPTH = 8
        private const val STEP_EM = 1.5f
    }
}
