package editor

import java.util.UUID

enum class ListKind { NONE, BULLET, NUMBER }
data class LinkValue(val id: String, val uri: String) {
    init { require(id.length in 1..64 && uri.length in 1..2048);require(runCatching { java.net.URI(uri).isAbsolute }.getOrDefault(false));require(uri.none { it.code<32 }) }
    fun activatable()=runCatching { java.net.URI(uri).scheme.lowercase() in setOf("http","https","mailto") }.getOrDefault(false)
}
data class ImageBlock(val resource: String, val width: Int = 240, val alt: String = "") {
    init { require(resource.length==64 && resource.all { it in "0123456789abcdef" });require(width in 10..600);require(alt.length<=2048) }
}
data class ImageResource(val data: String, val width: Int, val height: Int, val mime: String) {
    init { require(data.length<=6_000_000 && width in 1..8192 && height in 1..8192 && width.toLong()*height<=16_000_000);require(mime in setOf("image/png","image/jpeg")) }
    fun bytes()=java.util.Base64.getDecoder().decode(data)
}
fun freshStructureId(): String=UUID.randomUUID().toString()
data class OutlineEntry(val anchor: Long?, val level: Int, val label: String)
fun Document.outlineEntries()=paragraphs().filter { it.properties.heading>0 }.map { OutlineEntry(it.anchor,it.properties.heading,
    units.subList(it.start,it.end).joinToString("") { u -> u.char.toString() }.trim().ifEmpty { "Empty heading" }) }
fun Document.patchParagraphs(changes: Map<Long?,ParagraphProperties>):Document=copy(startParagraph=changes[rootBlockId]?:startParagraph,
    units=units.map { if(it.kind==UnitKind.PARAGRAPH_BREAK && changes.containsKey(it.paragraphAnchor))it.copy(followingParagraph=changes.getValue(it.paragraphAnchor)) else it })
fun Document.itemDepth(item:String):Int { val blocks=paragraphs();var id=item;var n=0;val seen=HashSet<String>()
    while(id.isNotEmpty()) { require(seen.add(id) && n<8) { "Invalid list ancestry" };val p=blocks.firstOrNull { it.properties.item==id } ?: error("Missing item parent");id=p.properties.parent;n++ };return n }
fun Document.listMarkers():Map<Long?,String> {
    val result=HashMap<Long?,String>();val seen=HashSet<String>();val counters=HashMap<String,Int>();val kinds=HashMap<String,ListKind>()
    for(p in paragraphs()) { val v=p.properties
        if(v.item.isEmpty()) { counters.clear();kinds.clear();continue }
        if(!seen.add(v.item))continue
        val key=v.parent
        val n=if(v.restart>0)v.restart else if(kinds[key]==v.list)(counters[key]?:0)+1 else 1
        counters[key]=n;kinds[key]=v.list;result[p.anchor]=if(v.list==ListKind.BULLET) "•" else "$n."
    };return result
}
fun validateStructure(d:Document) {
    require(d.units.size<=DocumentFiles.MAX_UNITS && d.resources.size<=32 && d.resources.values.sumOf { it.data.length.toLong() }<=12_000_000 && d.resources.values.sumOf { it.width.toLong()*it.height }<=16_000_000) { "Document resource budget exceeded" }
    val blocks=d.paragraphs();require(blocks.map { it.anchor }.distinct().size==blocks.size) { "Duplicate paragraph identity" };val items=blocks.filter { it.properties.item.isNotEmpty() }.groupBy { it.properties.item }
    for((id, ps) in items) { val p=ps.first().properties;require(ps.all { it.properties.list==p.list && it.properties.parent==p.parent && it.properties.restart==p.restart }) { "Inconsistent list item" }
        require(ps.all { it.properties.markerTypography==p.markerTypography }) { "Inconsistent marker typography" }
        require(p.parent.isEmpty() || items[p.parent]?.first()?.index?.let { it<ps.first().index }==true) { "Missing/late list parent" }; d.itemDepth(id)
        for(i in ps.first().index..ps.last().index) { var current=blocks[i].properties.item;val seen=HashSet<String>()
            while(current.isNotEmpty() && current!=id) { require(seen.add(current));current=items[current]?.first()?.properties?.parent ?: "" }
            require(current==id) { "Noncontiguous item paragraphs" }
        }
    }
    for(p in blocks) { val us=d.units.subList(p.start,p.end);if(us.any { it.kind==UnitKind.IMAGE })require(us.size==1 && us.single().image!=null && p.properties.heading==0) { "Image must occupy its own block" } }
    for(u in d.units) { require(u.kind==UnitKind.PARAGRAPH_BREAK || u.structuralBreak==StructuralBreak.PARAGRAPH);if(u.kind==UnitKind.PARAGRAPH_BREAK)require(u.char==if(u.structuralBreak==StructuralBreak.PAGE)'\u000c' else '\n');require((u.kind==UnitKind.IMAGE)==(u.image!=null));if(u.kind==UnitKind.IMAGE) { require(u.char=='\ufffc' && d.resources.containsKey(u.image!!.resource));require(u.style==TextStyleFlags()) } }
}
/** A caret recognizes only strict interior affinity. URI equality never merges IDs. */
fun Document.contiguousLinkAt(selection:Selection):Pair<LinkValue,Selection>? {
    if(!selection.collapsed)return null
    val at=selection.focus;val link=units.getOrNull(at-1)?.style?.link ?: return null
    if(units.getOrNull(at)?.style?.link!=link)return null
    var a=at;var b=at
    while(a>0 && units[a-1].kind==UnitKind.TEXT && units[a-1].style.link==link)a--
    while(b<units.size && units[b].kind==UnitKind.TEXT && units[b].style.link==link)b++
    return link to Selection(a,b)
}
sealed interface StructureCommand {
    data object PageBreak:StructureCommand
    data class Pagination(val rules:PaginationRules):StructureCommand
    data class Running(val value:RunningContent):StructureCommand
    data class Pages(val settings:PageSettings):StructureCommand
    data class ReplaceImage(val resource:String,val data:ImageResource):StructureCommand
    data class Role(val level:Int):StructureCommand
    data class ListType(val kind:ListKind):StructureCommand
    data object Indent:StructureCommand
    data object Outdent:StructureCommand
    data class Restart(val value:Int):StructureCommand
    data class MarkerStyle(val typography:MarkerTypography):StructureCommand
    data class Enter(val sameItem:Boolean):StructureCommand
    data class Link(val uri:String?,val label:String=""):StructureCommand
    data class InsertImage(val image:ImageBlock,val data:ImageResource):StructureCommand
    data class ImageProperties(val width:Int,val alt:String):StructureCommand
}
/** Pure semantic reducer; desktop controls only capture commands/targets. */
fun structureEdit(document:Document, target:Selection, anchors:List<Long?>, command:StructureCommand, allocate:()->Long):Pair<Document,Selection> {
    var d=document;var selection=target;val blocks=d.paragraphs();val selected=blocks.filter { it.anchor in anchors };require(selected.size==anchors.distinct().size) { "Paragraph target deleted" }
    fun change(ps:List<ParagraphBlock>, patch:(ParagraphProperties)->ParagraphProperties) { d=d.patchParagraphs(ps.associate { it.anchor to patch(it.properties) }) }
    fun selectedItems():Set<String> { val ids=selected.map { it.properties.item }.toSet();require("" !in ids) { "Select list items" };return ids }
    fun subtree(ids:Set<String>):Set<String> { val set=ids.toMutableSet();repeat(8) { blocks.filter { it.properties.parent in set }.forEach { set+=it.properties.item } };return set }
    when(command) {
        is StructureCommand.Running -> d=d.copy(running=command.value)
        is StructureCommand.Pagination -> change(selected) { it.copy(pagination=command.rules) }
        StructureCommand.PageBreak -> {
            val block=d.paragraphAt(target.start);require(d.units.subList(block.start,block.end).none { it.kind==UnitKind.IMAGE }) { "Insert a page break in a text paragraph" }
            val seed=if(target.start==0)d.startStyle else d.units[target.start-1].let { if(it.kind==UnitKind.PARAGRAPH_BREAK)it.paragraphStyle else it.style }
            val following=block.properties.copy(heading=0,pagination=block.properties.pagination.copy(breakBefore=false))
            d=d.splice(target.start,target.end,listOf(TextUnit(allocate(),'\u000c',UnitKind.PARAGRAPH_BREAK,paragraphStyle=seed,followingParagraph=following,structuralBreak=StructuralBreak.PAGE)))
            selection=Selection(target.start+1,target.start+1)
        }
        is StructureCommand.Pages -> d=d.copy(pageSettings=command.settings)
        is StructureCommand.ReplaceImage -> {
            val u=d.units.subList(target.start,target.end).singleOrNull()?.takeIf { it.kind==UnitKind.IMAGE } ?: error("Select one image")
            d=d.copy(resources=d.resources+(command.resource to command.data),units=d.units.map { if(it.id==u.id)it.copy(image=it.image!!.copy(resource=command.resource)) else it })
        }
        is StructureCommand.Role -> { require(command.level in 0..6);require(selected.none { p -> d.units.subList(p.start,p.end).any { it.kind==UnitKind.IMAGE } });change(selected) { it.copy(heading=command.level) } }
        is StructureCommand.ListType -> {
            val ids=selected.map { it.properties.item }.filter { it.isNotEmpty() }.toSet()
            val scope=blocks.filter { it.anchor in anchors || it.properties.item in ids }
            val seeds=scope.associate { p -> p.anchor to MarkerTypography.from(if(p.start==0)d.startStyle else d.units[p.start-1].paragraphStyle) }
            d=d.patchParagraphs(scope.associate { block -> val p=block.properties;block.anchor to if(command.kind==ListKind.NONE)p.copy(item="",parent="",list=ListKind.NONE,restart=0,markerTypography=MarkerTypography()) else p.copy(item=p.item.ifEmpty { freshStructureId() },list=command.kind,markerTypography=if(p.item.isEmpty())seeds.getValue(block.anchor) else p.markerTypography) })
            if(command.kind==ListKind.NONE) { val removed=ids;d=d.patchParagraphs(d.paragraphs().filter { it.properties.parent in removed }.associate { it.anchor to it.properties.copy(parent="") }) }
        }
        StructureCommand.Indent,StructureCommand.Outdent -> {
            val ids=selectedItems();val roots=ids.filter { id -> blocks.first { it.properties.item==id }.properties.parent !in ids }.toSet()
            val first=blocks.first { it.properties.item in roots };val parent=first.properties.parent
            require(roots.all { id -> blocks.first { it.properties.item==id }.properties.parent==parent }) { "Select sibling items" }
            val items=blocks.filter { it.properties.parent==parent && it.properties.item.isNotEmpty() }.distinctBy { it.properties.item }
            val rootOrder=items.filter { it.properties.item in roots };val positions=rootOrder.map { items.indexOf(it) };require(positions.zipWithNext().all { it.second==it.first+1 })
            val newParent=if(command==StructureCommand.Indent) { val previous=items.getOrNull(items.indexOf(first)-1) ?: error("No preceding sibling to indent into");previous.properties.item } else if(parent.isEmpty()) "" else blocks.first { it.properties.item==parent }.properties.parent
            change(blocks.filter { it.properties.item in roots }) { if(command==StructureCommand.Outdent && parent.isEmpty()) it.copy(item="",parent="",list=ListKind.NONE,restart=0,markerTypography=MarkerTypography()) else it.copy(parent=newParent) }
            if(command==StructureCommand.Outdent && parent.isEmpty())change(d.paragraphs().filter { it.properties.parent in roots }) { it.copy(parent="") }
            if(command==StructureCommand.Outdent && parent.isNotEmpty()) {
                val moving=subtree(roots);val parentTree=subtree(setOf(parent));val current=d.paragraphs()
                val moved=current.filter { it.properties.item in moving };val remaining=current.filter { it !in moved }.toMutableList()
                val after=remaining.indexOfLast { it.properties.item in parentTree };remaining.addAll(after+1,moved)
                val oldIds=d.units.mapIndexed { i,u->u.id to i }.toMap()
                val anchorId=target.anchor.let { d.units.getOrNull(it)?.id };val focusId=target.focus.let { d.units.getOrNull(it)?.id }
                val output=ArrayList<TextUnit>();var rootProps=remaining.first().properties
                remaining.forEachIndexed { index,block ->
                    if(index>0) { val sep=if(block.start>0)d.units[block.start-1] else null
                        output+= (sep?:TextUnit(allocate(),'\n',UnitKind.PARAGRAPH_BREAK)).copy(followingParagraph=block.properties,followingBlockId=block.anchor)
                    }
                    output.addAll(d.units.subList(block.start,block.end))
                }
                val rootStyle=if(remaining.first().start==0)d.startStyle else d.units[remaining.first().start-1].paragraphStyle
                d=d.copy(units=output,startParagraph=rootProps,startStyle=rootStyle,rootBlockId=remaining.first().anchor)
                fun map(id:Long?,fallback:Int)=id?.let { key->output.indexOfFirst { it.id==key }.takeIf { it>=0 } } ?: fallback.coerceAtMost(output.size)
                selection=Selection(map(anchorId,target.anchor),map(focusId,target.focus))
            }
        }
        is StructureCommand.Restart -> { require(command.value in 1..9999);val ids=selectedItems();change(blocks.filter { it.properties.item in ids }) { require(it.list==ListKind.NUMBER);it.copy(restart=command.value) } }
        is StructureCommand.MarkerStyle -> { val ids=selectedItems();change(blocks.filter { it.properties.item in ids }) { it.copy(markerTypography=command.typography) } }
        is StructureCommand.Enter -> {
            val a=target.start;val b=target.end;val p=d.paragraphAt(a);val props=p.properties
            if(!command.sameItem && props.item.isNotEmpty() && target.collapsed && blocks.filter { it.properties.item==props.item }.all { it.start==it.end })
                return structureEdit(d,target,anchors,StructureCommand.Outdent,allocate)
            var next=props.copy(pagination=props.pagination.copy(breakBefore=false,keepWithNext=false))
            if(props.heading>0 && a==p.end)next=next.copy(heading=0)
            if(props.item.isNotEmpty() && !command.sameItem) {
                val id=freshStructureId();next=next.copy(item=id,restart=0)
                val trailing=blocks.filter { it.index>p.index && it.properties.item==props.item }
                d=d.patchParagraphs(trailing.associate { it.anchor to it.properties.copy(item=id,restart=0) })
                d=d.patchParagraphs(d.paragraphs().filter { it.index>p.index && it.properties.parent==props.item }.associate { it.anchor to it.properties.copy(parent=id) })
            }
            require(d.units.subList(p.start,p.end).none { it.kind==UnitKind.IMAGE }) { "Use the neighboring text paragraph" }
            val seed=d.units.getOrNull(a-1)?.takeIf { it.kind==UnitKind.TEXT }?.style?.copy(link=null) ?: d.startStyle.copy(link=null)
            d=d.splice(a,b,listOf(TextUnit(allocate(),'\n',UnitKind.PARAGRAPH_BREAK,paragraphStyle=seed,followingParagraph=next)));selection=Selection(a+1,a+1)
        }
        is StructureCommand.Link -> {
            val link=command.uri?.let { LinkValue(freshStructureId(),it).also { l -> require(l.activatable()) { "Allowed schemes: https, http, mailto" } } }
            val current=d.contiguousLinkAt(target)
            val range=current?.second ?: target
            if(range.collapsed) { require(link!=null && command.label.isNotEmpty() && command.label.length<=4096 && command.label.none { it=='\n' || it=='\ufffc' })
                val seed=d.units.getOrNull(target.start-1)?.style ?: d.startStyle
                d=d.splice(target.start,target.end,command.label.map { TextUnit(allocate(),it,style=seed.copy(link=link)) });selection=Selection(target.start,target.start+command.label.length)
            } else {
                val ranges=displayedStyles(d).filter { it.start<range.end && it.end>range.start }
                d=d.copy(units=d.units.mapIndexed { i,u -> if(u.kind==UnitKind.TEXT && ranges.any { i in it.start until it.end })u.copy(style=u.style.copy(link=link)) else u })
            }
        }
        is StructureCommand.InsertImage -> {
            val a=target.start;val p=d.paragraphAt(a);val seed=d.units.getOrNull(a-1)?.style ?: d.startStyle
            val inserted=ArrayList<TextUnit>()
            if(a!=p.start)inserted+=TextUnit(allocate(),'\n',UnitKind.PARAGRAPH_BREAK,paragraphStyle=seed,followingParagraph=p.properties.copy(heading=0))
            inserted+=TextUnit(allocate(),'\ufffc',UnitKind.IMAGE,image=command.image)
            val suffix=d.paragraphAt(target.end)
            val retainTail=target.end<suffix.end
            val tailProperties=if(retainTail)suffix.properties else p.properties.copy(heading=0)
            val tailIdentity=if(a==p.start && retainTail)suffix.anchor else Long.MIN_VALUE
            inserted+=TextUnit(allocate(),'\n',UnitKind.PARAGRAPH_BREAK,paragraphStyle=seed.copy(link=null),followingParagraph=tailProperties,followingBlockId=tailIdentity)
            d=d.copy(resources=d.resources+(command.image.resource to command.data)).splice(a,target.end,inserted)
            if(a==p.start) {
                val first=p.properties.copy(heading=0)
                val identity=if(retainTail)allocate() else p.anchor
                if(a==0)d=d.copy(startParagraph=first,rootBlockId=identity)
                else d=d.copy(units=d.units.mapIndexed { i,u -> if(i==a-1)u.copy(followingParagraph=first,followingBlockId=identity) else u })
            }
            selection=Selection(a+if(a==p.start)0 else 1,a+if(a==p.start)1 else 2)
        }
        is StructureCommand.ImageProperties -> {
            val image=d.units.subList(target.start,target.end).singleOrNull { it.kind==UnitKind.IMAGE } ?: error("Select one image")
            d=d.copy(units=d.units.map { if(it.id==image.id) it.copy(image=it.image!!.copy(width=command.width,alt=command.alt)) else it })
        }
    }
    validateStructure(d);return d to selection
}
fun Document.plainText():String {
    val markers=listMarkers();return paragraphs().joinToString("") { p ->
        val separator=if(p.index==0)"" else if(units[p.start-1].structuralBreak==StructuralBreak.PAGE)"\u000c" else "\n"
        val prefix=markers[p.anchor]?.let { "  ".repeat((itemDepth(p.properties.item)-1).coerceAtLeast(0))+it+" " } ?: ""
        separator+prefix+buildString { var previous:LinkValue?=null
            for(u in units.subList(p.start,p.end)) { if(previous!=null && previous!=u.style.link)append(" <${previous.uri}>")
                if(u.kind==UnitKind.IMAGE)append("[Image: ${u.image!!.alt.ifEmpty { "No alternative text" }}]") else append(u.char)
                previous=u.style.link }
            if(previous!=null)append(" <${previous.uri}>")
        }
    }
}

fun Document.repairListParents(before:Document):Document {
    val current=paragraphs();val previous=before.paragraphs()
    val currentHeads=current.mapTo(HashSet()) { it.anchor }
    val priorItems=previous.filter { it.properties.item.isNotEmpty() }.groupBy { it.properties.item }
    val properties=current.filter { it.properties.item.isNotEmpty() }.associate { it.properties.item to it.properties }
    val ownerByUnit=HashMap<Long,String>()
    for(p in current)for(i in p.start until (p.end+if(p.hasBreak)1 else 0))ownerByUnit[units[i].id]=p.properties.item
    val mergedInto=HashMap<String,String>()
    // A consumed first leaf can leave continuation paragraphs behind. Direct
    // sibling joins move those paragraphs and children into the surviving left
    // item; original provenance must not resurrect the emptied right container.
    for((id,ps) in priorItems) {
        val first=ps.first();if(first.anchor in currentHeads)continue
        val owner=(first.start until (first.end+if(first.hasBreak)1 else 0)).firstNotNullOfOrNull {
            ownerByUnit[before.units[it].id]?.takeIf { item->item.isNotEmpty() && item!=id }
        } ?: continue
        val left=properties[owner] ?: continue
        if(left.parent==first.properties.parent && left.list==first.properties.list)mergedInto[id]=owner
    }
    fun merged(id:String):String { var value=id;val seen=HashSet<String>()
        while(value in mergedInto) { require(seen.add(value));value=mergedInto.getValue(value) };return value }
    val surviving=properties.keys.mapTo(HashSet()) { merged(it) }
    fun parentOf(id:String):String { var value=merged(id);val seen=HashSet<String>()
        while(value.isNotEmpty() && value !in surviving) { require(seen.add(value));value=merged(priorItems[value]?.first()?.properties?.parent ?: "") };return value }
    return patchParagraphs(current.associate { p -> val original=p.properties;val item=merged(original.item)
        val owner=properties[item]
        val moved=if(item!=original.item && owner!=null)original.copy(item=item,parent=owner.parent,list=owner.list,restart=owner.restart,markerTypography=owner.markerTypography) else original
        p.anchor to moved.copy(parent=parentOf(moved.parent)) })
}

/** Removed object heads cannot absorb a surviving right text leaf. Follow retained
 * unit/boundary provenance; without retained units use the last merged source leaf. */
fun Document.repairRemovedImageHeads(before:Document, end:Int):Document {
    if(before.units.none { it.kind==UnitKind.IMAGE })return this
    val surviving=units.mapTo(HashSet()) { it.id }
    val prior=before.paragraphs()
    val removed=prior.filter { p -> p.start<p.end && before.units[p.start].kind==UnitKind.IMAGE && before.units[p.start].id !in surviving && p.hasBreak && before.units[p.end].id !in surviving }
    if(removed.isEmpty())return this
    val groups=paragraphs().associateBy { it.anchor };val sourceByUnit=HashMap<Long,ParagraphBlock>()
    for(p in prior)for(i in p.start until p.end+if(p.hasBreak)1 else 0)sourceByUnit[before.units[i].id]=p
    val fallback=before.paragraphAt(end)
    var result=this
    for(old in removed) {
        val group=groups[old.anchor] ?: continue
        val retained=(group.start until group.end+if(group.hasBreak)1 else 0).firstNotNullOfOrNull { sourceByUnit[units[it].id] }
        val head=retained ?: fallback.takeIf { it.index>old.index } ?: continue
        if(head.index==old.index)continue
        val typing=if(head.start==0)before.startStyle else before.units[head.start-1].paragraphStyle
        result=if(group.start==0)result.copy(startParagraph=head.properties,rootBlockId=head.anchor,startStyle=typing)
            else result.copy(units=result.units.mapIndexed { i,u -> if(i==group.start-1)u.copy(followingParagraph=head.properties,followingBlockId=head.anchor,paragraphStyle=typing) else u })
    }
    return result
}
