package editor

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection

/** Presentation only: marker ownership and root-side nesting never alter raw text. */
data class ListMarkerLayout(val text:String,val layout:TextLayoutResult?,val left:Float,val top:Float,val width:Float,val height:Float,
    val baseline:Float,val bulletRadius:Float,val opticalCenter:Float,val nominalSize:Int,val fontKey:String,val rtl:Boolean)
data class ListColumn(val left:Float,val width:Float,val rootRtl:Boolean,val gutter:Float)
internal data class ListNominal(val font:StoredFont,val size:Int)
internal fun Document.listNominal(item:String,defaultSize:Int):ListNominal {
    val seed=paragraphs().first { it.properties.item==item }.properties.markerTypography
    return ListNominal(seed.font,seed.size)
}
/** Old formats migrate exactly once, from the first substantive cluster, never a vote. */
internal fun Document.migrateMarkerTypography():Document {
    val seeds=paragraphs().filter { it.properties.item.isNotEmpty() }.groupBy { it.properties.item }.mapValues { (_,ps) ->
        val first=ps.first();val raw=units.subList(first.start,first.end)
        val at=raw.indexOfFirst { !it.char.isWhitespace() && Character.getType(it.char)!=Character.FORMAT.toInt() && !it.char.isISOControl() }
        MarkerTypography.from(if(at>=0)raw[at].style else if(first.start==0)startStyle else units[first.start-1].paragraphStyle)
    }
    return patchParagraphs(paragraphs().filter { it.properties.item.isNotEmpty() }.associate { it.anchor to it.properties.copy(markerTypography=seeds.getValue(it.properties.item)) })
}
internal fun Document.listRoot(item:String):String { val props=paragraphs().filter { it.properties.item.isNotEmpty() }.associate { it.properties.item to it.properties };var id=item;val seen=HashSet<String>()
    while(props.getValue(id).parent.isNotEmpty()) { require(seen.add(id));id=props.getValue(id).parent };return id }
