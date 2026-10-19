# M1 Web input/AT compatibility spike

This bounded experiment compares real Chromium event data with the actual JVM EditorEngine over a local JSON-line pipe. The browser is a native-input reference surface; this is not a shipped Web adapter or another editable production authority.

The private package pins Playwright 1.62.1. `npm ci --ignore-scripts --no-audit --no-fund` installs its JavaScript dependencies. The runner uses the Chromium executable already present in this host's Playwright cache; it does not download/install an OS component. With an identity-verified fresh desktop build:

```powershell
python validation/web/input/verify.py --build-dir <fresh-build> --output-dir <new-evidence-directory>
```

The integrated verifier runs it against the same fresh classes as Desktop/U0. The spike:

- Captures browser-generated beforeinput ranges and input results from DevTools keyboard delivery, then replays the actual data through the JVM owner and compares every result.
- Drives Chromium's real composition manager through CDP, capturing candidate detours and terminal data. The actual callback sequence replays through EditorEngine with exact text, attribution, selection, one-session Undo/Redo and stamped stale-callback refusal.
- Uses MutationObserver to prove that the protected publication policy leaves the active DOM subtree untouched.
- Changes the real renderer policy for a negative control. Rebuilding active content sends the later commit to a different location (`日本ABC` instead of `A日本C` on the recorded host). The observed snapshot/oracle is not altered to produce this result.
- Publishes settled flat-body frames from the actual JVM owner, including computed display attributes and forward/backward selection. Eight publications cover real browser keyboard replay, composition settlement, Undo/Redo, mixed Urdu with diacritics/emoji, UTXT-123 isolates, UTXT-131 LRM and empty content. Each Chromium AX textbox must expose the exact owner value and multiline/editable state. Browser publication must leave the owner snapshot unchanged.
- Defers the frame publisher during composition and while another control has focus. Stale session revisions and unsupported structural flows cannot alter the DOM or selection. The previous plain-text reference publisher remains only as the active-composition negative control.

CDP-driven composition is synthetic relative to **OS IME qualification**, even when Chromium marks its generated events trusted. This does not qualify a real Urdu keyboard layout or Japanese OS candidate selection. Partial commitment/terminal ordering across engines, a live semantic-DOM adapter, rich flow/tree projection, native bidi selection, clipboard and OS AT remain open. The primary Nastaliq font is pinned; general fallback quality is not qualified by this probe.

Every run records source/font/dependency/build identities, runtime versions, events and owner responses. The browser and owner process are closed in the finalizer; their confirmed closed state is checked. They do not remain running while unrelated repository work continues.

The plain-text settled-publication baseline lost bold styling and moved the browser selection from [3,3] to [0,0]. Restoring a full owner frame fixes this bounded projection. A second browser control reproduced focus theft: publishing a frame after clicking Other focus target performed a DOM mutation and returned focus to the editor. The publisher now defers without mutations while unfocused; it does not invent a focus-regain/IME settlement policy. A caller must retry an eligible current frame after focus returns.

This publisher remains a private feasibility function in the probe HTML. It accepts flat text with B/I display runs and UTF-16 selection only; structural paragraphs/richer flows explicitly reject before changing the DOM. It does not connect live beforeinput events to an asynchronous owner, implement pointer/native selection feedback or qualify browser undo integration. The JVM still owns history. Typed malformed frames, multiple live owners and portable schema/session identity are not public contracts of this experiment. Actual browser AX readbacks and a mixed-Urdu screenshot are retained per run; OS screen-reader speech and browser-native bidi navigation remain separate gates.
