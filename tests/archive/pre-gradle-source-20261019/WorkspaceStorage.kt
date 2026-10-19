package editor

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID

/** Private local envelopes. Not Java serialization, a public API, or a power-loss guarantee. */
internal object WorkspaceBytes {
    const val MAX = 24_000_000
    fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun pack(magic:Int, body:(DataOutputStream)->Unit):ByteArray {
        val b=ByteArrayOutputStream();DataOutputStream(b).use { it.writeInt(magic);it.writeInt(1);body(it) }
        val raw=b.toByteArray();require(raw.size<=MAX);return raw+MessageDigest.getInstance("SHA-256").digest(raw)
    }
    fun <T> unpack(b:ByteArray,magic:Int,body:(DataInputStream)->T):T {
        require(b.size in 40..MAX+32) { "Incomplete/oversized local record" }
        val raw=b.copyOfRange(0,b.size-32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(raw),b.copyOfRange(raw.size,b.size))) { "Local record checksum failed" }
        return DataInputStream(ByteArrayInputStream(raw)).use { i -> require(i.readInt()==magic && i.readInt()==1) { "Unsupported local record version" };body(i).also { require(i.available()==0) { "Trailing local record data" } } }
    }
    fun text(o:DataOutputStream,s:String,max:Int=32768) { require(s.length<=max);o.writeInt(s.length);s.forEach { o.writeChar(it.code) } }
    fun text(i:DataInputStream,max:Int=32768):String { val n=i.readInt();require(n in 0..max && n.toLong()*2<=i.available());return CharArray(n) { i.readChar() }.concatToString() }
    fun bytes(o:DataOutputStream,b:ByteArray) { require(b.size<=MAX);o.writeInt(b.size);o.write(b) }
    fun bytes(i:DataInputStream):ByteArray { val n=i.readInt();require(n in 0..MAX && n<=i.available());return i.readNBytes(n) }
    fun read(p:Path,max:Int=MAX+32)=Files.newInputStream(p).use { it.readNBytes(max+1).also { b->require(b.size<=max) } }
    fun atomic(p:Path,b:ByteArray,interruption:(String)->Unit={},replace:Boolean=true) {
        check(!javax.swing.SwingUtilities.isEventDispatchThread()) { "Workspace IO on EDT" }
        Files.createDirectories(p.parent)
        val tmp=Files.createTempFile(p.parent,".publication-",".tmp")
        try {
            interruption("before-write")
            FileChannel.open(tmp,StandardOpenOption.WRITE).use { c ->
                val half=b.size/2
                fun write(start:Int,n:Int) { val buffer=ByteBuffer.wrap(b,start,n);while(buffer.hasRemaining())c.write(buffer) }
                write(0,half);interruption("mid-write");write(half,b.size-half);c.force(true)
            }
            interruption("before-publish")
            if(replace)Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            else { check(!Files.exists(p));Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE) }
            interruption("after-publish")
        } finally { Files.deleteIfExists(tmp) }
    }
    fun uuid(s:String)=s.also { require(UUID.fromString(s).toString()==s) }
}

internal data class FileFingerprint(val exists:Boolean,val size:Long=0,val sha256:String="") {
    override fun toString()=if(exists) "$size bytes · SHA256 ${sha256.take(16)}…" else "Missing file"
    companion object {
        fun capture(path:Path):FileFingerprint {
            check(!javax.swing.SwingUtilities.isEventDispatchThread())
            if(!Files.exists(path))return FileFingerprint(false)
            require(Files.isRegularFile(path)) { "Destination is not a regular file" }
            val digest=MessageDigest.getInstance("SHA-256");var size=0L
            Files.newInputStream(path).use { stream -> val b=ByteArray(65536);while(true) { val n=stream.read(b);if(n<0)break;size+=n;require(size<=DocumentFiles.MAX_ENCODED_BYTES) { "Destination exceeds prototype file budget" };digest.update(b,0,n) } }
            return FileFingerprint(true,size,digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
internal class FileConflict(val destination:Path,val expected:FileFingerprint,val actual:FileFingerprint):IOException("File conflict: $destination\nExpected: $expected\nCurrent: $actual\nKeep editing, Reload, or Save a Copy.")

/** Cooperating instances serialize validation/publication. Arbitrary writers can still race the final rename. */
internal object SafeDocumentIO {
    private val monitor=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private val opened=java.util.IdentityHashMap<Document,Pair<Path,FileFingerprint>>()
    @Synchronized fun remember(d:Document,p:Path,b:ByteArray) { if(opened.size>=256)opened.clear();opened[d]=p.toAbsolutePath().normalize() to FileFingerprint(true,b.size.toLong(),WorkspaceBytes.hash(b)) }
    @Synchronized fun basis(d:Document,p:Path)=opened[d]?.takeIf { it.first==p.toAbsolutePath().normalize() }?.second
    fun write(path:Path,d:Document,expected:FileFingerprint,interrupt:(String)->Unit={}):FileFingerprint {
        check(!javax.swing.SwingUtilities.isEventDispatchThread())
        val p=path.toAbsolutePath().normalize();val key=p.toString().let { if(System.getProperty("os.name").startsWith("Windows"))it.lowercase(java.util.Locale.ROOT) else it }
        val locks=Path.of(System.getProperty("astra.lockRoot",LocalRecoveryStore.defaultRoot().parent.resolve("file-locks").toString()))
        Files.createDirectories(locks)
        synchronized(monitor.computeIfAbsent(key) { Any() }) {
            FileChannel.open(locks.resolve(WorkspaceBytes.hash(key.toByteArray())+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE).use { ch -> ch.lock().use {
                fun validate() { val actual=FileFingerprint.capture(p);if(actual!=expected)throw FileConflict(p,expected,actual) }
                validate();val bytes=DocumentFiles.encode(d)
                WorkspaceBytes.atomic(p,bytes,{ stage -> interrupt(stage);if(stage=="before-publish")validate() })
                return FileFingerprint(true,bytes.size.toLong(),WorkspaceBytes.hash(bytes))
            } }
        }
    }
}

/** Recovery-only identity sidecar around the existing validated document codec. */
internal object ExactDocument {
    fun encode(d:Document)=WorkspaceBytes.pack(0x41584544) { o ->
        WorkspaceBytes.bytes(o,DocumentFiles.encode(d));o.writeBoolean(d.rootBlockId!=null);d.rootBlockId?.let(o::writeLong)
        o.writeInt(d.units.size);d.units.forEach { o.writeLong(it.id);o.writeBoolean(it.followingBlockId!=null);it.followingBlockId?.let(o::writeLong) }
    }
    fun decode(b:ByteArray)=WorkspaceBytes.unpack(b,0x41584544) { i ->
        val d=DocumentFiles.decode(WorkspaceBytes.bytes(i));val root=if(i.readBoolean())i.readLong() else null
        require(i.readInt()==d.units.size);val units=d.units.map { it.copy(id=i.readLong(),followingBlockId=if(i.readBoolean())i.readLong() else null) }
        require(units.map { it.id }.distinct().size==units.size)
        d.copy(units=units,rootBlockId=root).also(::validateStructure)
    }
}
