package editor

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.TextPainter
import java.awt.image.BufferedImage
import java.awt.print.*
import java.io.ByteArrayInputStream
import org.jetbrains.skia.*

/** Immutable Skia pictures recorded from the actual editor plan, not remeasured text. */
class PrintSnapshot private constructor(val plan:DocumentPagePlan,private val pictures:List<Picture>):AutoCloseable {
    private var closed=false
    @Synchronized fun render(page:Int,dpi:Int=120):BufferedImage {
        check(!closed);require(page in pictures.indices && dpi in 72..300)
        val factor=dpi/96f;val width=kotlin.math.ceil(plan.width*factor).toInt();val height=kotlin.math.ceil(plan.height*factor).toInt()
        require(width.toLong()*height<=16_000_000)
        return Surface.makeRasterN32Premul(width,height).use { surface ->
            surface.canvas.clear(0xffffffff.toInt());surface.canvas.scale(factor,factor);pictures[page].playback(surface.canvas)
            surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> javax.imageio.ImageIO.read(ByteArrayInputStream(data.bytes)) } }
        }
    }
    fun paperFormat():PageFormat {
        val paper=Paper();paper.setSize(documentToPoints(plan.width).toDouble(),documentToPoints(plan.height).toDouble());paper.setImageableArea(0.0,0.0,paper.width,paper.height)
        return PageFormat().apply { this.paper=paper;orientation=PageFormat.PORTRAIT }
    }
    fun warnings(format:PageFormat):List<String> = buildList {
        if(kotlin.math.abs(format.width-documentToPoints(plan.width))>.25 || kotlin.math.abs(format.height-documentToPoints(plan.height))>.25)add("Printer paper differs from the captured document; pages will not be repaginated")
        if(format.imageableX>0 || format.imageableY>0 || format.imageableWidth<format.width || format.imageableHeight<format.height)add("Printer has unprintable margins; actual size may clip. Fit scales uniformly to the printable area")
    }
    fun pageable(first:Int=1,last:Int=plan.pageCount,format:PageFormat=paperFormat(),fit:Boolean=false,cancelled:()->Boolean={false}):Pageable {
        require(first in 1..plan.pageCount && last in first..plan.pageCount)
        return object:Pageable {
            override fun getNumberOfPages()=last-first+1
            override fun getPageFormat(index:Int):PageFormat { require(index in 0 until numberOfPages);return format.clone() as PageFormat }
            override fun getPrintable(index:Int):Printable { require(index in 0 until numberOfPages)
                return Printable { graphics,actual,n ->
                    if(cancelled())throw PrinterAbortException("Cancelled")
                    if(n !in 0 until numberOfPages)Printable.NO_SUCH_PAGE else {
                        val image=render(first-1+n,300);val g=graphics.create() as java.awt.Graphics2D
                        try {
                            val width=documentToPoints(plan.width).toDouble();val height=documentToPoints(plan.height).toDouble()
                            val factor=if(fit)minOf(actual.imageableWidth/width,actual.imageableHeight/height) else 1.0
                            if(fit)g.translate(actual.imageableX,actual.imageableY)
                            g.scale(factor*width/image.width,factor*height/image.height);g.drawImage(image,0,0,null)
                        } finally { g.dispose();image.flush() }
                        Printable.PAGE_EXISTS
                    }
                }
            }
        }
    }
    @Synchronized override fun close() { if(!closed) { closed=true;pictures.forEach { it.close() } } }
    class Builder(val plan:DocumentPagePlan):AutoCloseable {
        private val pictures=ArrayList<Picture>();private var handedOff=false
        private val images=plan.document.units.mapNotNull { it.image?.resource }.distinct().associateWith { requireNotNull(ImageResources.cached(it)) { "Image decoding pending; wait and retry preview" } }
        val completed get()=pictures.size
        init { require(plan.pageCount<=200) { "Bounded preview/print supports at most200 pages" };require(outputUnicodeIssues(plan.document).isEmpty()) { "Output refused; source unchanged. ${outputUnicodeIssues(plan.document).take(8).joinToString("; ")}" };plan.assertCoverage() }
        fun next() {
            check(javax.swing.SwingUtilities.isEventDispatchThread()) { "Capture paragraph pictures on the editor owner thread" };check(!handedOff && completed<plan.pageCount)
            val page=completed
            PictureRecorder().use { recorder ->
                val canvas=recorder.beginRecording(Rect.makeWH(plan.width,plan.height));canvas.clear(0xffffffff.toInt())
                for(f in plan.fragments.filter { it.page==page }) {
                    val p=plan.paragraphs[f.paragraphIndex];val y=plan.margin+f.top-page*(plan.height+plan.gap)
                    canvas.save();canvas.clipRect(Rect.makeLTRB(0f,(y-2f).coerceAtLeast(0f),plan.width,(y+f.height+2f).coerceAtMost(plan.height)))
                    if(p.image!=null) {
                        Image.makeFromBitmap(images.getValue(p.image.resource).asSkiaBitmap()).use { image -> canvas.drawImageRect(image,Rect.makeXYWH(plan.margin+p.imageLeft,y,p.imageWidth,p.height)) }
                    } else {
                        canvas.save();canvas.translate(plan.margin+p.contentLeft,y-f.sourceTop);synchronized(p.layout.multiParagraph) { TextPainter.paint(canvas.asComposeCanvas(),p.layout) };canvas.restore()
                    }
                    if(f.firstLine==0)p.marker?.let { marker ->
                        if(marker.layout!=null) { canvas.save();canvas.translate(plan.margin+marker.left,y+marker.top);synchronized(marker.layout.multiParagraph) { TextPainter.paint(canvas.asComposeCanvas(),marker.layout) };canvas.restore() }
                        else Paint().use { paint -> paint.color=0xff202b3c.toInt();canvas.drawCircle(plan.margin+marker.left+marker.bulletRadius,y+marker.opticalCenter,marker.bulletRadius,paint) }
                    };canvas.restore()
                }
                for(r in plan.running.filter { it.page==page }) { canvas.save();canvas.translate(r.left,r.top);synchronized(r.layout.multiParagraph) { TextPainter.paint(canvas.asComposeCanvas(),r.layout) };canvas.restore() }
                pictures+=recorder.finishRecordingAsPicture()
            }
            require(pictures.sumOf { it.approximateBytesUsed.toLong() }<=64L*1024*1024) { "Captured print pictures exceed64MiB" }
        }
        fun finish():PrintSnapshot { check(completed==plan.pageCount && !handedOff);handedOff=true;return PrintSnapshot(plan,pictures.toList()) }
        override fun close() { if(!handedOff)pictures.forEach { it.close() };handedOff=true }
    }
}
