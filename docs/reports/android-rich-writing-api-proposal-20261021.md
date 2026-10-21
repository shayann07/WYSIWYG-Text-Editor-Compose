# Proposed additive Android integration APIs

Approved by the owner on 21 October 2026, including API-dump updates. Implementation must
retain compatible signatures, provider conflict/safety protection on every write route,
actionable recovery failures, metadata-only listing, worker clipboard IO and stale-basis rejection.
These additions preserve existing signatures and the verificationEngine compatibility escape
hatch. No new runtime dependencies, production schema freeze or versioned specification change.

## Existing APIs reused without changes

EditorIntent.SetTextStyle/SetParagraphProperties/Structure/ApplyStructure/ApplyClipboard,
ReplaceFound, NavigateOutline and all current read-only queries cover the document commands.
Touch, word selection, layout, marker columns and renderer details remain internal.

## Proposed core/data Android exports

```kotlin
data class AndroidDocumentRead(val document: Document, val sha256: String)
class AndroidUriRepository(context: Context, privateRoot: File) : DocumentRepository {
    override fun load(location: String): Document
    override fun save(location: String, document: Document)
    fun read(location: String): AndroidDocumentRead
    fun write(location: String, document: Document, expectedSha256: String?): String
    fun readBytes(location: String, limit: Int): ByteArray
    fun writeBytes(location: String, bytes: ByteArray)
}
data class AndroidRecoveryEntry(
    val owner: String, val sequence: Long, val timestamp: Long, val name: String,
    val hasProposal: Boolean, val byteCount: Long,
    val warning: String? = null,
)
data class AndroidRecoveryRecord(
    val entry: AndroidRecoveryEntry, val document: Document,
    val proposal: Document?, val evidence: String,
)
class AndroidRecoveryStore(root: File, quotaBytes: Long = 64L * 1024 * 1024) {
    fun checkpoint(owner: String, sequence: Long, name: String, document: Document,
                   proposal: Document? = null, evidence: String = ""): Boolean
    fun entries(): List<AndroidRecoveryEntry>
    fun read(owner: String, sequence: Long): AndroidRecoveryRecord
    fun discard(owner: String, sequence: Long): Boolean
    fun export(owner: String, sequence: Long): ByteArray
}
class AndroidClipboardFiles(context: Context, authority: String) {
    fun publish(bytes: ByteArray): Uri
    fun clear()
}
class AndroidClipboardProvider : ContentProvider // standard provider overrides only
```

URI methods are worker-thread-only. `write` requires the captured source digest; null admits
an empty newly created target only. Local safety copies precede overwrite. No provider atomicity
claim. Generic byte ports support bounded image reads and explicit recovery-envelope export.

Recovery records own full accepted/resource bytes and a separately labelled proposal/evidence.
The entry/loaded-record split implements the owner's explicit bounded-memory listing requirement.
All disk methods run off UI. Exact sequence matching protects restore/export/discard races;
failed operations retain bytes. Export is a recovery envelope, not canonical proposal text.
Checkpoint returns true on completed/idempotent publication; failures throw actionable reasons.
Resolved owner/sequence identities cannot be reused for different content.

AndroidClipboardProvider is registered by consumer-owned manifest authority, read-only with
temporary grants. Published URIs outlive the source process, with bounded private storage.
Explicit clear revokes only this app's issued resource grants and removes its immutable clipboard
blobs; pasted documents, history and recovery retain their own resources. Full storage refuses
new rich publication until explicitly cleared; there is no silent eviction.

The existing JVM `FragmentCodec` is made available on Android with its current signatures and
versioned bytes, by sharing the JVM-compatible codec implementation (no AWT/Swing).

## Proposed core/ui Android additions

```kotlin
// Additions to existing AndroidEditorView; no existing constructor/signature changes.
var fragmentCodec: RichFragmentCodec?
var publishRichClipboard: ((ByteArray) -> Uri)?
fun showSearch(basis: FindBasis?, matches: List<FindMatch>, active: Int)
fun reveal(range: Selection)
```

The clipboard publisher is an injected IO port, not a core/ui dependency on core/data.
Clipboard operations capture owner/basis on UI, use bounded worker IO, publish through the
actual Android system clipboard and validate again before Cut/Paste. Search highlighting and
reveal are presentation only and reject stale/foreign bases. They never create text/history.

Consumer UI, document-picker launch/result routing, permissions and dialogs remain app chrome.
The independent APK consumes these APIs through packaged metadata, not repository fixtures.
