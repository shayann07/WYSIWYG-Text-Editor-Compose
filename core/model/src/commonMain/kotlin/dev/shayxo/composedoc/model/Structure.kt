package dev.shayxo.composedoc.model

enum class ListKind { NONE, BULLET, NUMBER }
data class LinkValue(val id: String, val uri: String) {
    init {
        require(id.length in 1..64 && uri.length in 1..2048)
        require(isAbsoluteDocumentUri(uri))
        require(uri.none { it.code < 32 })
    }
    fun activatable() = uri.substringBefore(':').lowercase() in setOf("http", "https", "mailto")
}
data class ImageBlock(val resource: String, val width: Int = 240, val alt: String = "") {
    init {
        require(resource.length == 64 && resource.all { it in "0123456789abcdef" })
        require(width in 10..600)
        require(alt.length <= 2048)
    }
}
data class ImageResource(val data: String, val width: Int, val height: Int, val mime: String) {
    init {
        require(data.length <= 6_000_000 && width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_000_000)
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
    units = units.map { if (it.kind == UnitKind.PARAGRAPH_BREAK && changes.containsKey(it.paragraphAnchor))it.copy(followingParagraph = changes.getValue(it.paragraphAnchor)) else it },
)
fun Document.itemDepth(item: String): Int {
    val blocks = paragraphs()
    var id = item
    var n = 0
    val seen = HashSet<String>()
    while (id.isNotEmpty()) {
        require(seen.add(id) && n < 8) { "Invalid list ancestry" }
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
            continue
        }
        if (!seen.add(v.item))continue
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
        result[p.anchor] = if (v.list == ListKind.BULLET) "•" else "$n."
    }
    return result
}
fun validateStructure(d: Document) {
    require(d.units.size <= DocumentLimits.MAX_UNITS && d.resources.size <= 32 && d.resources.values.sumOf { it.data.length.toLong() } <= 12_000_000 && d.resources.values.sumOf { it.width.toLong() * it.height } <= 16_000_000) { "Document resource budget exceeded" }
    val blocks = d.paragraphs()
    require(blocks.map { it.anchor }.distinct().size == blocks.size) { "Duplicate paragraph identity" }
    val items = blocks.filter { it.properties.item.isNotEmpty() }.groupBy { it.properties.item }
    for ((id, ps) in items) {
        val p = ps.first().properties
        require(ps.all { it.properties.list == p.list && it.properties.parent == p.parent && it.properties.restart == p.restart }) { "Inconsistent list item" }
        require(ps.all { it.properties.markerTypography == p.markerTypography }) { "Inconsistent marker typography" }
        require(p.parent.isEmpty() || items[p.parent]?.first()?.index?.let { it < ps.first().index } == true) { "Missing/late list parent" }
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
    for (p in blocks) {
        val us = d.units.subList(p.start, p.end)
        if (us.any { it.kind == UnitKind.IMAGE })require(us.size == 1 && us.single().image != null && p.properties.heading == 0) { "Image must occupy its own block" }
    }
    for (u in d.units) {
        require(u.kind == UnitKind.PARAGRAPH_BREAK || u.structuralBreak == StructuralBreak.PARAGRAPH)
        if (u.kind == UnitKind.PARAGRAPH_BREAK)require(u.char == if (u.structuralBreak == StructuralBreak.PAGE)'\u000c' else '\n')
        require((u.kind == UnitKind.IMAGE) == (u.image != null))
        if (u.kind == UnitKind.IMAGE) {
            require(u.char == '\ufffc' && d.resources.containsKey(u.image!!.resource))
            require(u.style == TextStyleFlags())
        }
    }
}

/** A caret recognizes only strict interior affinity. URI equality never merges IDs. */
fun Document.contiguousLinkAt(selection: Selection): Pair<LinkValue, Selection>? {
    if (!selection.collapsed)return null
    val at = selection.focus
    val link = units.getOrNull(at - 1)?.style?.link ?: return null
    if (units.getOrNull(at)?.style?.link != link)return null
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
        val prefix = markers[p.anchor]?.let { "  ".repeat((itemDepth(p.properties.item) - 1).coerceAtLeast(0)) + it + " " } ?: ""
        separator + prefix + buildString {
            var previous: LinkValue? = null
            for (u in units.subList(p.start, p.end)) {
                if (previous != null && previous != u.style.link)append(" <${previous.uri}>")
                if (u.kind == UnitKind.IMAGE)append("[Image: ${u.image!!.alt.ifEmpty { "No alternative text" }}]") else append(u.char)
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
    val properties = current.filter { it.properties.item.isNotEmpty() }.associate { it.properties.item to it.properties }
    val ownerByUnit = HashMap<Long, String>()
    for (p in current)for (i in p.start until (p.end + if (p.hasBreak)1 else 0))ownerByUnit[units[i].id] = p.properties.item
    val mergedInto = HashMap<String, String>()
    // A consumed first leaf can leave continuation paragraphs behind. Direct
    // sibling joins move those paragraphs and children into the surviving left
    // item; original provenance must not resurrect the emptied right container.
    for ((id, ps) in priorItems) {
        val first = ps.first()
        if (first.anchor in currentHeads)continue
        val owner = (first.start until (first.end + if (first.hasBreak)1 else 0)).firstNotNullOfOrNull {
            ownerByUnit[before.units[it].id]?.takeIf { item -> item.isNotEmpty() && item != id }
        } ?: continue
        val left = properties[owner] ?: continue
        if (left.parent == first.properties.parent && left.list == first.properties.list)mergedInto[id] = owner
    }
    fun merged(id: String): String {
        var value = id
        val seen = HashSet<String>()
        while (value in mergedInto) {
            require(seen.add(value))
            value = mergedInto.getValue(value)
        }
        return value
    }
    val surviving = properties.keys.mapTo(HashSet()) { merged(it) }
    fun parentOf(id: String): String {
        var value = merged(id)
        val seen = HashSet<String>()
        while (value.isNotEmpty() && value !in surviving) {
            require(seen.add(value))
            value = merged(priorItems[value]?.first()?.properties?.parent ?: "")
        }
        return value
    }
    return patchParagraphs(
        current.associate { p ->
            val original = p.properties
            val item = merged(original.item)
            val owner = properties[item]
            val moved = if (item != original.item && owner != null)original.copy(item = item, parent = owner.parent, list = owner.list, restart = owner.restart, markerTypography = owner.markerTypography) else original
            p.anchor to moved.copy(parent = parentOf(moved.parent))
        },
    )
}

/** Removed object heads cannot absorb a surviving right text leaf. Follow retained
 * unit/boundary provenance; without retained units use the last merged source leaf. */
fun Document.repairRemovedImageHeads(before: Document, end: Int): Document {
    if (before.units.none { it.kind == UnitKind.IMAGE })return this
    val surviving = units.mapTo(HashSet()) { it.id }
    val prior = before.paragraphs()
    val removed = prior.filter { p -> p.start < p.end && before.units[p.start].kind == UnitKind.IMAGE && before.units[p.start].id !in surviving && p.hasBreak && before.units[p.end].id !in surviving }
    if (removed.isEmpty())return this
    val groups = paragraphs().associateBy { it.anchor }
    val sourceByUnit = HashMap<Long, ParagraphBlock>()
    for (p in prior)for (i in p.start until p.end + if (p.hasBreak)1 else 0)sourceByUnit[before.units[i].id] = p
    val fallback = before.paragraphAt(end)
    var result = this
    for (old in removed) {
        val group = groups[old.anchor] ?: continue
        val retained = (group.start until group.end + if (group.hasBreak)1 else 0).firstNotNullOfOrNull { sourceByUnit[units[it].id] }
        val head = retained ?: fallback.takeIf { it.index > old.index } ?: continue
        if (head.index == old.index)continue
        val typing = if (head.start == 0)before.startStyle else before.units[head.start - 1].paragraphStyle
        result = if (group.start == 0) {
            result.copy(startParagraph = head.properties, rootBlockId = head.anchor, startStyle = typing)
        } else {
            result.copy(units = result.units.mapIndexed { i, u -> if (i == group.start - 1)u.copy(followingParagraph = head.properties, followingBlockId = head.anchor, paragraphStyle = typing) else u })
        }
    }
    return result
}
