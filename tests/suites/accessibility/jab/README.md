# Native Windows Java Access Bridge spike (F39 / M1)

This tests the installed JDK's real Windows Access Bridge DLL through its official ABI, in a separate JDK 23 tooling client. It addresses an external native AT boundary; it is not a screen reader or general desktop-control framework. The client finds only the fixture's unique title and verifies its HWND belongs to the exact process spawned by this test. It does not enumerate or operate on unrelated windows, send keyboard/mouse input, change focus, or activate persistent accessibility settings.

The fixture is the actual AWT/Compose editor, paged with pinned Noto Nastaliq, offscreen and without focus acquisition. Only its JVM receives `javax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge`. No `jabswitch` command or OS component installation occurs. The user's `.accessibility.properties` identity is compared before/after. The native client uses the existing JDK Foreign Function & Memory API; no new native helper/compiler/runtime dependency is installed. DLLs, SDK headers, compiler, JDK source and build/source identities are recorded.

Seven executed checks cover:

- Native traversal finds exactly one Document editor, then chunked reads preserve the exact UTF-16 document.
- A page-boundary character rectangle crosses the bridge in device pixels, using the installed JDK's documented/source-observed coordinate conversion.
- Native attribute reads preserve stored inline and paragraph-break bold/italic flags plus requested Noto Nastaliq Urdu 26.
- Switching among system, Noto Nastaliq, Gulzar and Noto Sans Arabic is reflected in native requested-family/size reads without document/history mutation. Typed Java notification delivery is tested separately; native callback subscription is not claimed.
- Native selection and whole-text replacement reach the existing owner; fixture reducer Undo/Redo restores exact attributed/structural states and selection in one transaction.
- A native empty replacement is a meaningful deletion with exact Undo/Redo.
- All four malformed UTXT-151–154 specimens survive native WCHAR/Java transport with their raw units intact.

The first traversal experiment exposed a real defect: the surface returned an AccessibleContext but did not implement Java's Accessible marker. Standard window-tree traversal skipped it. The fixed surface is now discoverable; regressions walk from the window rather than obtaining the editor directly.

The first native geometry assertion compared JAB device pixels with Java logical coordinates and correctly failed the assertion. Installed JDK source shows conversion of local and component-screen rectangles separately before translation. The corrected test compares equivalent device-space coordinates exactly and retains logical coordinates and scale diagnostics. This is bridge conversion fidelity, not an independent shaping/layout oracle or a multi-monitor qualification.

The installed JDK passes half-open selection endpoints through to AccessibleEditableText (the source and native observations agree), despite inclusive wording in some Oracle API prose. Text-range reads use inclusive endpoints, and the client converts chunk bounds explicitly. This does not change the editor's existing Java half-open range contract. JAB's WCHAR API has a 1023-unit replacement limit and cannot represent embedded NUL; the tooling client rejects unsupported inputs instead of silently truncating them.

Run `python desktop-prototype/verify.py` for the same fresh isolated build as the general and Urdu checks. A focused invocation accepts `--build-dir <fresh-build> --output-dir <new-directory>` on this script. Non-Windows or missing existing SDK/compiler capability is NOT RUN. Logs retain both process protocols and commands. Both owned processes must exit normally; every acquired AccessibleContext cookie is released, and the client's arena unloads its DLL. Watchdogs bound both fixtures.

Remaining limitations: no actual screen-reader speech/braille, system clipboard, native keyboard/IME, concurrent upstream SDK timing, multi-monitor geometry or other-platform qualification. JAB queues some calls on the EDT before entering the editor API; the editor's own queue-basis test does not establish end-to-end protection for a third party's earlier unstamped read. The initial metadata experiment reproduced SDK defaults of Monospaced 12 while the renderer requested Noto Nastaliq Urdu 26. Explicit requested family/size attributes repair that mismatch. Physical per-glyph fallback faces remain unqualified; the requested profile must not be treated as a resolved-face report. The app's default process is not given bridge activation automatically, and F39 remains open.

Primary reference: [Java 23 Access Bridge API](https://docs.oracle.com/en/java/javase/23/access/java-access-bridge-api.html). Installed `%JAVA_HOME%/include/win32/bridge` and `lib/src.zip` provide the version-specific ABI/dispatch/DPI evidence. This tooling client and fixture are not public library APIs or a production activation policy.
