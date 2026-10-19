package editor

import java.awt.*

/** Pure preview math in document dp. The adapter supplies AWT pixels and exactly one zoom transform. */
internal class ImageResizeGesture(val capture:ClipboardCapture,val image:ImageBlock,val startX:Int,val zoom:Float,val rightHandle:Boolean) {
    init { require(zoom.isFinite() && zoom>0f && zoom<=1f) }
    fun width(x:Int)= (image.width+(x-startX)*(if(rightHandle)1 else -1)/zoom).toInt().coerceIn(10,600)
    fun finish(engine:EditorEngine,x:Int):Boolean {
        if(engine.clipboardCapture()!=capture)return false
        val n=width(x);return n==image.width || engine.applyStructure(capture,StructureCommand.ImageProperties(n,image.alt))
    }
}

internal data class ImageHandleGeometry(val bounds:Rectangle,val left:Rectangle,val right:Rectangle)
internal fun imageHandles(surface:EditorSurface):ImageHandleGeometry? {
    val f=surface.engine.snapshot();if(!f.selection.let { it.end-it.start==1 } || f.marked!=null)return null
    val p=surface.geometry()?.paragraphs?.firstOrNull { it.startOffset==f.selection.start && it.image!=null } ?: return null
    val g=surface.geometry() ?: return null;val fragment=g.fragments.first { it.paragraphIndex==p.index }
    val bounds=Rectangle(((g.x+p.imageLeft)/g.scale).toInt(),((g.y+fragment.top)/g.scale).toInt(),(p.imageWidth/g.scale).toInt().coerceAtLeast(1),(p.height/g.scale).toInt().coerceAtLeast(1))
    val cy=bounds.y+bounds.height/2
    return ImageHandleGeometry(bounds,Rectangle(bounds.x-6,cy-6,12,12),Rectangle(bounds.x+bounds.width-6,cy-6,12,12))
}
