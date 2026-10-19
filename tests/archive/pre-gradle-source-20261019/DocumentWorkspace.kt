package editor

import java.io.*
import java.nio.file.*
import java.util.UUID

enum class PaperSize { A4, LETTER }
data class PageSettings(val paper:PaperSize=PaperSize.A4,val landscape:Boolean=false,val marginMm:Int=14) {
    init { require(marginMm in 5..40) { "Margins must be 5–40 mm" } }
    val widthMm get()=if(landscape)heightPortrait else widthPortrait
    val heightMm get()=if(landscape)widthPortrait else heightPortrait
    private val widthPortrait get()=if(paper==PaperSize.A4)210f else 215.9f
    private val heightPortrait get()=if(paper==PaperSize.A4)297f else 279.4f
}

/** Independent unsaved content: owner, units, paragraph, list and link identities are freshly allocated. */
internal fun cloneDocument(source:Document):Document {
    val units=source.units.map { freshCloneId.getAndIncrement() }
    val blocks=source.paragraphs().associate { it.anchor to freshCloneId.getAndIncrement() }
    val items=source.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.distinct().associateWith { freshStructureId() }
    val links=HashMap<String,String>()
    fun style(s:TextStyleFlags)=s.copy(link=s.link?.let { it.copy(id=links.getOrPut(it.id) { freshStructureId() }) })
    fun p(p:ParagraphProperties)=p.copy(item=items[p.item]?:"",parent=items[p.parent]?:"")
    return source.copy(units=source.units.mapIndexed { at,u->u.copy(id=units[at],style=style(u.style),paragraphStyle=style(u.paragraphStyle),followingParagraph=p(u.followingParagraph),followingBlockId=if(u.kind==UnitKind.PARAGRAPH_BREAK)blocks.getValue(u.paragraphAnchor) else Long.MIN_VALUE) },
        rootBlockId=blocks.getValue(source.rootBlockId),startStyle=style(source.startStyle),startParagraph=p(source.startParagraph),resources=source.resources.toMap(),running=source.running.fresh()).also(::validateStructure)
}
private val freshCloneId=java.util.concurrent.atomic.AtomicLong(1_000_000_000L)

internal data class TemplateEntry(val id:String,val label:String,val builtIn:Boolean) { override fun toString()=label }
internal class TemplateStore(val root:Path) {
    val builtIns=listOf(TemplateEntry("blank","Blank Urdu",true),TemplateEntry("letter","Urdu Letter",true),TemplateEntry("article","Urdu Article / Report",true))
    fun list():List<TemplateEntry> {
        if(!Files.exists(root))return builtIns
        val user=Files.list(root).use { files->files.filter { it.fileName.toString().endsWith(".template") }.limit(129).toList() };require(user.size<=128)
        return builtIns+user.map { path->val id=path.fileName.toString().removeSuffix(".template");WorkspaceBytes.uuid(id)
            WorkspaceBytes.unpack(WorkspaceBytes.read(path),0x4154544d) { i -> val label=WorkspaceBytes.text(i,128);WorkspaceBytes.bytes(i);TemplateEntry(id,label,false) } }
    }
    fun source(entry:TemplateEntry):Document {
        if(entry.builtIn) { require(entry in builtIns);return builtIn(entry.id) }
        return WorkspaceBytes.unpack(WorkspaceBytes.read(root.resolve(WorkspaceBytes.uuid(entry.id)+".template")),0x4154544d) { i->WorkspaceBytes.text(i,128);DocumentFiles.decode(WorkspaceBytes.bytes(i)) }
    }
    fun create(entry:TemplateEntry)=cloneDocument(source(entry))
    fun save(label:String,d:Document):TemplateEntry {
        require(label.isNotBlank() && label.length<=128);check(list().size<131) { "User template limit reached (128)" }
        val id=UUID.randomUUID().toString();val b=WorkspaceBytes.pack(0x4154544d) { o->WorkspaceBytes.text(o,label,128);WorkspaceBytes.bytes(o,DocumentFiles.encode(d)) }
        WorkspaceBytes.atomic(root.resolve("$id.template"),b,replace=false);return TemplateEntry(id,label,false)
    }
    fun remove(entry:TemplateEntry) { require(!entry.builtIn);Files.delete(root.resolve(WorkspaceBytes.uuid(entry.id)+".template")) }
    private fun builtIn(id:String):Document {
        val e=EditorEngine();e.setTextStyle(TextStylePatch(font=StoredFont.NOTO_NASTALIQ));e.setParagraphProperties(ParagraphDirection.RTL,ParagraphAlignment.START)
        if(id=="blank")return e.snapshot().committed.copy(startStyle=TextStyleFlags(font=StoredFont.NOTO_NASTALIQ,size=26))
        val text=if(id=="letter") "خط کا عنوان\nتاریخ: ۱۹ اکتوبر ۲۰۲۶\nمحترم دوست،\nیہاں اپنا پیغام لکھیں۔\nنیک تمناؤں کے ساتھ،\nآپ کا نام\nOriginal template — not expert linguistically reviewed." else "مضمون کا عنوان\nمصنف کا نام — تاریخ\nتعارف\nیہاں اپنے مضمون کا تعارف لکھیں۔\nاہم نکات\nپہلا نکتہ\nدوسرا نکتہ\nEnglish notes — café, 123 and اردو\nنتیجہ\nیہاں اپنی بات مکمل کریں۔\nOriginal template — not expert linguistically reviewed."
        e.replace(text,"Template",paragraphBreaks=true)
        fun at(label:String,command:StructureCommand) { val n=text.indexOf(label);e.move(Selection(n,n));e.structure(command) }
        at(if(id=="letter")"خط کا عنوان" else "مضمون کا عنوان",StructureCommand.Role(1))
        if(id=="article") {
            listOf("تعارف","اہم نکات","نتیجہ").forEach { at(it,StructureCommand.Role(2)) }
            listOf("پہلا نکتہ","دوسرا نکتہ").forEach { at(it,StructureCommand.ListType(ListKind.BULLET)) }
            val n=text.indexOf("English notes");e.move(Selection(n,n));e.setParagraphProperties(ParagraphDirection.LTR,ParagraphAlignment.START)
        }
        val result=e.snapshot().committed
        val ids=result.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.distinct().mapIndexed { index,value->value to UUID.nameUUIDFromBytes(("builtin-template-v1:$id:$index").toByteArray()).toString() }.toMap()
        return result.patchParagraphs(result.paragraphs().associate { it.anchor to it.properties.copy(item=ids[it.properties.item]?:"",parent=ids[it.properties.parent]?:"") })
    }
}

internal data class WorkspaceEntry(val id:String,val path:String?,val name:String,val fingerprint:FileFingerprint?,val opened:Long,
    val recovery:String,val unfinished:String,val selection:Selection=Selection(0,0),val scroll:Float=0f,val paged:Boolean=true,val zoom:Float=1f,val font:String="noto-nastaliq",val active:Boolean=true,val processId:Long=ProcessHandle.current().pid(),val viewportWidth:Int=0,val viewportHeight:Int=0,val contentHash:String="") {
    override fun toString()="$name${if(path!=null)" — $path" else " (unsaved recovery)"} · ${if(active)"open at last checkpoint" else "deliberately closed"}"
}
/** Bounded two-slot index, merged under a process lock. Paths are references, not owner identities. */
internal class WorkspaceStore(val root:Path) {
    @Volatile var warning:String?=null;private set
    private val monitor=globalMonitors.computeIfAbsent(root.toAbsolutePath().normalize().toString()) { Any() }
    companion object { private val globalMonitors=java.util.concurrent.ConcurrentHashMap<String,Any>() }
    private fun encode(generation:Long,entries:List<WorkspaceEntry>)=WorkspaceBytes.pack(0x41545753) { o ->
        o.writeLong(generation);o.writeInt(entries.size)
        for(e in entries) { WorkspaceBytes.text(o,e.id,36);o.writeBoolean(e.path!=null);e.path?.let { WorkspaceBytes.text(o,it) };WorkspaceBytes.text(o,e.name,512)
            o.writeBoolean(e.fingerprint!=null);e.fingerprint?.let { o.writeBoolean(it.exists);o.writeLong(it.size);WorkspaceBytes.text(o,it.sha256,64) }
            o.writeLong(e.opened);WorkspaceBytes.text(o,e.recovery,36);WorkspaceBytes.text(o,e.unfinished,36);o.writeInt(e.selection.anchor);o.writeInt(e.selection.focus);o.writeBoolean(e.selection.primary);o.writeByte(e.selection.wrapAffinity.ordinal);o.writeFloat(e.scroll);o.writeBoolean(e.paged);o.writeFloat(e.zoom);WorkspaceBytes.text(o,e.font,64);o.writeBoolean(e.active);o.writeLong(e.processId);o.writeInt(e.viewportWidth);o.writeInt(e.viewportHeight);WorkspaceBytes.text(o,e.contentHash,64)
        }
    }
    private fun read():Pair<Long,List<WorkspaceEntry>> {
        var failures=0
        val valid=(0..1).mapNotNull { slot->val p=root.resolve("workspace-$slot.bin");if(!Files.exists(p))null else runCatching {
            WorkspaceBytes.unpack(WorkspaceBytes.read(p,4*1024*1024),0x41545753) { i ->
                val g=i.readLong();require(g>0);val n=i.readInt();require(n in 0..128)
                g to List(n) {
                    val id=WorkspaceBytes.uuid(WorkspaceBytes.text(i,36));val path=if(i.readBoolean())WorkspaceBytes.text(i) else null;val name=WorkspaceBytes.text(i,512)
                    val fp=if(i.readBoolean())FileFingerprint(i.readBoolean(),i.readLong(),WorkspaceBytes.text(i,64)) else null
                    val time=i.readLong();val r=WorkspaceBytes.uuid(WorkspaceBytes.text(i,36));val u=WorkspaceBytes.uuid(WorkspaceBytes.text(i,36));val a=i.readInt();val f=i.readInt();val primary=i.readBoolean();val affinity=i.readUnsignedByte();require(affinity in WrapAffinity.entries.indices);val scroll=i.readFloat();val paged=i.readBoolean();val zoom=i.readFloat();val font=WorkspaceBytes.text(i,64);val active=i.readBoolean();val process=i.readLong();require(process>0);val vw=i.readInt();val vh=i.readInt();require(vw in 0..16384 && vh in 0..16384);val content=WorkspaceBytes.text(i,64);require(content.isEmpty() || content.matches(Regex("[0-9a-f]{64}")))
                    require(a in 0..100000 && f in 0..100000 && scroll.isFinite() && scroll>=0 && zoom in setOf(.5f,.75f,1f) && font in TypographyProfile.labels.keys)
                    WorkspaceEntry(id,path,name,fp,time,r,u,Selection(a,f,primary,WrapAffinity.entries[affinity]),scroll,paged,zoom,font,active,process,vw,vh,content)
                }.also { require(it.map { it.id }.distinct().size==it.size) }
            }
        }.getOrElse { failures++;null } }
        warning=if(failures==0)null else if(valid.isEmpty()) "Workspace index unreadable. Documents and recovery records are intact; open/recover them explicitly." else "Workspace index used its valid fallback after an interrupted/corrupt publication."
        return valid.maxByOrNull { it.first } ?: (0L to emptyList())
    }
    fun entries()=locked { read().second }
    fun update(entry:WorkspaceEntry,interrupt:(String)->Unit={})=mutate(interrupt) { old ->
        val replacement=old.filter { it.id!=entry.id }+entry
        check(replacement.count { it.active }<=32) { "Workspace has 32 active window references; close a window first" }
        // Recent history may be bounded, but unresolved/active window references cannot be evicted.
        replacement.sortedByDescending { it.opened }.let { all->all.filter { it.active }+all.filter { !it.active }.take((128-all.count { it.active }).coerceAtLeast(0)) }
    }
    fun remove(id:String)=mutate { it.filter { e->e.id!=id } }
    fun clear()=mutate { emptyList() }
    private fun mutate(interrupt:(String)->Unit={},change:(List<WorkspaceEntry>)->List<WorkspaceEntry>)=locked {
        val (g,old)=read();val entries=change(old);require(entries.size<=128);WorkspaceBytes.atomic(root.resolve("workspace-${(g+1)%2}.bin"),encode(g+1,entries),interrupt)
    }
    private fun <T> locked(action:()->T):T { check(!javax.swing.SwingUtilities.isEventDispatchThread());Files.createDirectories(root)
        synchronized(monitor) { java.nio.channels.FileChannel.open(root.resolve("index.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE).use { c->c.lock().use { return action() } } }
    }
}

internal fun workspacePositionsValid(entry:WorkspaceEntry,document:Document)=entry.contentHash.isNotEmpty() && entry.contentHash==WorkspaceBytes.hash(DocumentFiles.encode(document)) && entry.selection.end<=document.units.size
