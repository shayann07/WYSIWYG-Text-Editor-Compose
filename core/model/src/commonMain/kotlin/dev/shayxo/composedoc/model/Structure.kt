package dev.shayxo.composedoc.model

private const val MAX_LINK_ID_LENGTH = 64
private const val MAX_URI_LENGTH = 2048
private const val FIRST_NON_C0_CONTROL = 32
private const val RESOURCE_HASH_LENGTH = 64
private const val MIN_IMAGE_WIDTH = 10
private const val MAX_IMAGE_WIDTH = 600
private const val MAX_ALT_TEXT_LENGTH = 2048
private const val MAX_RESOURCE_DATA_LENGTH = 6_000_000
private const val MAX_IMAGE_DIMENSION = 8192
private const val MAX_IMAGE_PIXELS = 16_000_000L
private const val MAX_LIST_DEPTH = 8
private const val MAX_DOCUMENT_RESOURCES = 32
private const val MAX_DOCUMENT_RESOURCE_DATA = 12_000_000L
private const val MAX_DOCUMENT_PIXELS = 16_000_000L

enum class ListKind { NONE, BULLET, NUMBER }
data class LinkValue(val id: String, val uri: String) {
    init {
        require(id.length in 1..MAX_LINK_ID_LENGTH && uri.length in 1..MAX_URI_LENGTH)
        require(isAbsoluteDocumentUri(uri))
        require(uri.none { it.code < FIRST_NON_C0_CONTROL })
    }
    fun activatable() = uri.substringBefore(':').lowercase() in setOf("http", "https", "mailto")
}
data class ImageBlock(val resource: String, val width: Int = 240, val alt: String = "") {
    init {
        require(resource.length == RESOURCE_HASH_LENGTH && resource.all { it in "0123456789abcdef" })
        require(width in MIN_IMAGE_WIDTH..MAX_IMAGE_WIDTH)
        require(alt.length <= MAX_ALT_TEXT_LENGTH)
    }
}
data class ImageResource(val data: String, val width: Int, val height: Int, val mime: String) {
    init {
        require(
            data.length <= MAX_RESOURCE_DATA_LENGTH &&
                width in 1..MAX_IMAGE_DIMENSION &&
                height in 1..MAX_IMAGE_DIMENSION &&
                width.toLong() * height <= MAX_IMAGE_PIXELS,
        )
        require(mime in setOf("image/png", "image/jpeg"))
    }
    fun bytes() = kotlin.io.encoding.Base64.decode(data)
}
fun freshStructureId(): String = newStructureIdentity()
data class OutlineEntry(val anchor: Long?, val level: Int, val label: String)
fun Document.outlineEntries() = paragraphs().filter { it.properties.heading > 0 }.map {
    OutlineEntry(
        it.anchor,
        it.properties.heading,
        units.subList(it.start, it.end).joinToString("") { u -> u.char.toString() }.trim().ifEmpty { "Empty heading" },
    )
}
fun Document.patchParagraphs(changes: Map<Long?, ParagraphProperties>): Document = copy(
    startParagraph = changes[rootBlockId] ?: startParagraph,
    units = units.map {
        if (it.kind == UnitKind.PARAGRAPH_BREAK &&
            changes.containsKey(it.paragraphAnchor)
        ) {
            it.copy(followingParagraph = changes.getValue(it.paragraphAnchor))
        } else {
            it
        }
    },
)
fun Document.itemDepth(item: String): Int {
    val blocks = paragraphs()
    var id = item
    var n = 0
    val seen = HashSet<String>()
    while (id.isNotEmpty()) {
        require(seen.add(id) && n < MAX_LIST_DEPTH) { "Invalid list ancestry" }
        val p = blocks.firstOrNull { it.properties.item == id } ?: error("Missing item parent")
        id = p.properties.parent
        n++
    }
    return n
}
fun Document.listMarkers(): Map<Long?, String> {
    val result = HashMap<Long?, String>()
    val seen = HashSet<String>()
    val counters = HashMap<String, Int>()
    val kinds = HashMap<String, ListKind>()
    for (p in paragraphs()) {
        val v = p.properties
        if (v.item.isEmpty()) {
            counters.clear()
            kinds.clear()
        } else if (seen.add(v.item)) {
            result[p.anchor] = nextMarker(v, counters, kinds)
        }
    }
    return result
}

private fun nextMarker(
    v: ParagraphProperties,
    counters: HashMap<String, Int>,
    kinds: HashMap<String, ListKind>,
): String {
    val key = v.parent
    val n = if (v.restart > 0) {
        v.restart
    } else if (kinds[key] == v.list) {
        (counters[key] ?: 0) + 1
    } else {
        1
    }
    counters[key] = n
    kinds[key] = v.list
    return if (v.list == ListKind.BULLET) "•" else "$n."
}
fun validateStructure(d: Document) {
    validateResourceBudget(d)
    val blocks = d.paragraphs()
    require(blocks.map { it.anchor }.distinct().size == blocks.size) { "Duplicate paragraph identity" }
    val items = blocks.filter { it.properties.item.isNotEmpty() }.groupBy { it.properties.item }
    for ((id, ps) in items) validateListItem(d, blocks, items, id, ps)
    validateImageBlocks(d, blocks)
    for (u in d.units) validateUnit(d, u)
}

private fun validateResourceBudget(d: Document) {
    require(
        d.units.size <= DocumentLimits.MAX_UNITS &&
            d.resources.size <= MAX_DOCUMENT_RESOURCES &&
            d.resources.values.sumOf { it.data.length.toLong() } <= MAX_DOCUMENT_RESOURCE_DATA &&
            d.resources.values.sumOf {
                it.width.toLong() * it.height
            } <= MAX_DOCUMENT_PIXELS,
    ) { "Document resource budget exceeded" }
}

private fun validateListItem(
    d: Document,
    blocks: List<ParagraphBlock>,
    items: Map<String, List<ParagraphBlock>>,
    id: String,
    ps: List<ParagraphBlock>,
) {
    val p = ps.first().properties
    require(
        ps.all {
            it.properties.list == p.list &&
                it.properties.parent == p.parent &&
                it.properties.restart == p.restart
        },
    ) { "Inconsistent list item" }
    require(ps.all { it.properties.markerTypography == p.markerTypography }) { "Inconsistent marker typography" }
    require(
        p.parent.isEmpty() ||
            items[p.parent]?.first()?.index?.let {
                it < ps.first().index
            } == true,
    ) { "Missing/late list parent" }
    d.itemDepth(id)
    for (i in ps.first().index..ps.last().index) {
        var current = blocks[i].properties.item
        val seen = HashSet<String>()
        while (current.isNotEmpty() && current != id) {
            require(seen.add(current))
            current = items[current]?.first()?.properties?.parent ?: ""
        }
        require(current == id) { "Noncontiguous item paragraphs" }
    }
}

private fun validateImageBlocks(d: Document, blocks: List<ParagraphBlock>) {
    for (p in blocks) {
        val us = d.units.subList(p.start, p.end)
        if (us.any { it.kind == UnitKind.IMAGE }) {
            require(
                us.size == 1 && us.single().image != null && p.properties.heading == 0,
            ) {
                "Image must occupy its own block"
            }
        }
    }
}

private fun validateUnit(d: Document, u: TextUnit) {
    require(u.kind == UnitKind.PARAGRAPH_BREAK || u.structuralBreak == StructuralBreak.PARAGRAPH)
    if (u.kind ==
        UnitKind.PARAGRAPH_BREAK
    ) {
        require(u.char == if (u.structuralBreak == StructuralBreak.PAGE)'\u000c' else '\n')
    }
    require((u.kind == UnitKind.IMAGE) == (u.image != null))
    if (u.kind == UnitKind.IMAGE) {
        require(u.char == '\ufffc' && d.resources.containsKey(u.image!!.resource))
        require(u.style == TextStyleFlags())
    }
}

/** A caret recognizes only strict interior affinity. URI equality never merges IDs. */
fun Document.contiguousLinkAt(selection: Selection): Pair<LinkValue, Selection>? {
    val at = selection.focus
    val link = if (selection.collapsed) units.getOrNull(at - 1)?.style?.link else null
    if (link == null || units.getOrNull(at)?.style?.link != link) return null
    var a = at
    var b = at
    while (a > 0 && units[a - 1].kind == UnitKind.TEXT && units[a - 1].style.link == link)a--
    while (b < units.size && units[b].kind == UnitKind.TEXT && units[b].style.link == link)b++
    return link to Selection(a, b)
}

fun Document.plainText(): String {
    val markers = listMarkers()
    return paragraphs().joinToString("") { p ->
        val separator = if (p.index == 0) {
            ""
        } else if (units[p.start - 1].structuralBreak == StructuralBreak.PAGE) {
            "\u000c"
        } else {
            "\n"
        }
        val prefix =
            markers[p.anchor]?.let { "  ".repeat((itemDepth(p.properties.item) - 1).coerceAtLeast(0)) + it + " " } ?: ""
        separator + prefix + buildString {
            var previous: LinkValue? = null
            for (u in units.subList(p.start, p.end)) {
                if (previous != null && previous != u.style.link)append(" <${previous.uri}>")
                if (u.kind ==
                    UnitKind.IMAGE
                ) {
                    append("[Image: ${u.image!!.alt.ifEmpty { "No alternative text" }}]")
                } else {
                    append(u.char)
                }
                previous = u.style.link
            }
            if (previous != null)append(" <${previous.uri}>")
        }
    }
}

fun Document.repairListParents(before: Document): Document {
    val current = paragraphs()
    val previous = before.paragraphs()
    val currentHeads = current.mapTo(HashSet()) { it.anchor }
    val priorItems = previous.filter { it.properties.item.isNotEmpty() }.groupBy { it.properties.item }
    val properties = current.filter {
        it.properties.item.isNotEmpty()
    }.associate { it.properties.item to it.properties }
    val ownerByUnit = ownersByUnit(units, current)
    val mergedInto = HashMap<String, String>()
    // A consumed first leaf can leave continuation paragraphs behind. Direct
    // sibling joins move those paragraphs and children into the surviving left
    // item; original provenance must not resurrect the emptied right container.
    for ((id, ps) in priorItems) {
        val first = ps.first()
        if (first.anchor !in currentHeads) {
            siblingMergeTarget(before, id, first, ownerByUnit, properties)?.let { mergedInto[id] = it }
        }
    }
    val surviving = properties.keys.mapTo(HashSet()) { mergedItem(mergedInto, it) }
    return patchParagraphs(
        current.associate { p ->
            val original = p.properties
            val item = mergedItem(mergedInto, original.item)
            val moved = movedItemProperties(original, item, properties[item])
            p.anchor to moved.copy(parent = survivingParent(moved.parent, mergedInto, surviving, priorItems))
        },
    )
}

private fun ownersByUnit(units: List<TextUnit>, current: List<ParagraphBlock>): HashMap<Long, String> {
    val ownerByUnit = HashMap<Long, String>()
    for (p in current) {
        for (i in p.start until (p.end + if (p.hasBreak)1 else 0)) {
            ownerByUnit[units[i].id] =
                p.properties.item
        }
    }
    return ownerByUnit
}

private fun siblingMergeTarget(
    before: Document,
    id: String,
    first: ParagraphBlock,
    ownerByUnit: Map<Long, String>,
    properties: Map<String, ParagraphProperties>,
): String? {
    val owner = (first.start until (first.end + if (first.hasBreak)1 else 0)).firstNotNullOfOrNull {
        ownerByUnit[before.units[it].id]?.takeIf { item -> item.isNotEmpty() && item != id }
    }
    val left = owner?.let { properties[it] }
    return owner.takeIf {
        left != null && left.parent == first.properties.parent && left.list == first.properties.list
    }
}

private fun mergedItem(mergedInto: Map<String, String>, id: String): String {
    var value = id
    val seen = HashSet<String>()
    while (value in mergedInto) {
        require(seen.add(value))
        value = mergedInto.getValue(value)
    }
    return value
}

private fun survivingParent(
    id: String,
    mergedInto: Map<String, String>,
    surviving: Set<String>,
    priorItems: Map<String, List<ParagraphBlock>>,
): String {
    var value = mergedItem(mergedInto, id)
    val seen = HashSet<String>()
    while (value.isNotEmpty() && value !in surviving) {
        require(seen.add(value))
        value = mergedItem(mergedInto, priorItems[value]?.first()?.properties?.parent ?: "")
    }
    return value
}

private fun movedItemProperties(
    original: ParagraphProperties,
    item: String,
    owner: ParagraphProperties?,
): ParagraphProperties = if (item != original.item &&
    owner != null
) {
    original.copy(
        item = item,
        parent = owner.parent,
        list = owner.list,
        restart = owner.restart,
        markerTypography = owner.markerTypography,
    )
} else {
    original
}

/** Removed object heads cannot absorb a surviving right text leaf. Follow retained
 * unit/boundary provenance; without retained units use the last merged source leaf. */
fun Document.repairRemovedImageHeads(before: Document, end: Int): Document {
    if (before.units.none { it.kind == UnitKind.IMAGE })return this
    val surviving = units.mapTo(HashSet()) { it.id }
    val prior = before.paragraphs()
    val removed = prior.filter { p ->
        p.start < p.end &&
            before.units[p.start].kind == UnitKind.IMAGE &&
            before.units[p.start].id !in surviving &&
            p.hasBreak &&
            before.units[p.end].id !in surviving
    }
    return if (removed.isEmpty()) this else reattachRemovedHeads(before, end, prior, removed)
}

private fun Document.reattachRemovedHeads(
    before: Document,
    end: Int,
    prior: List<ParagraphBlock>,
    removed: List<ParagraphBlock>,
): Document {
    val groups = paragraphs().associateBy { it.anchor }
    val sourceByUnit = HashMap<Long, ParagraphBlock>()
    for (p in prior)for (i in p.start until p.end + if (p.hasBreak)1 else 0)sourceByUnit[before.units[i].id] = p
    val fallback = before.paragraphAt(end)
    var result = this
    for (old in removed) {
        val group = groups[old.anchor]
        val head = group?.let { replacementHead(it, old, sourceByUnit, fallback) }
        if (group != null && head != null) result = result.withReplacementHead(before, group, head)
    }
    return result
}

private fun Document.replacementHead(
    group: ParagraphBlock,
    old: ParagraphBlock,
    sourceByUnit: Map<Long, ParagraphBlock>,
    fallback: ParagraphBlock,
): ParagraphBlock? {
    val retained = (group.start until group.end + if (group.hasBreak)1 else 0).firstNotNullOfOrNull {
        sourceByUnit[units[it].id]
    }
    val head = retained ?: fallback.takeIf { it.index > old.index }
    return head?.takeIf { it.index != old.index }
}

private fun Document.withReplacementHead(before: Document, group: ParagraphBlock, head: ParagraphBlock): Document {
    val typing = if (head.start == 0)before.startStyle else before.units[head.start - 1].paragraphStyle
    return if (group.start == 0) {
        copy(startParagraph = head.properties, rootBlockId = head.anchor, startStyle = typing)
    } else {
        copy(
            units = units.mapIndexed { i, u ->
                if (i ==
                    group.start - 1
                ) {
                    u.copy(
                        followingParagraph = head.properties,
                        followingBlockId = head.anchor,
                        paragraphStyle = typing,
                    )
                } else {
                    u
                }
            },
        )
    }
}
