import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

const root = path.resolve(import.meta.dirname, '../../..');
const option = name => process.argv.find(a => a.startsWith(`--${name}=`))?.slice(name.length + 3);
const build = path.resolve(option('build'));
const output = path.resolve(option('output'));
fs.mkdirSync(output, {recursive: false});
const sha = value => createHash('sha256').update(value).digest('hex');
const font = path.join(root, 'validation/urdu/u0/fonts/NotoNastaliqUrdu[wght].ttf');
const report = {status: 'RUNNING', gate: 'M1_WEB_INPUT_SPIKE', startedAt: new Date().toISOString(),
  sourceSha256: sha(fs.readFileSync(import.meta.filename)), fontSha256: sha(fs.readFileSync(font)),
  scope: 'Headless Chromium real keyboard events and CDP-driven composition manager, compared with actual JVM EditorEngine; not an OS IME or screen reader', checks: []};
report.nodeVersion = process.version;
report.platform = process.platform;
report.buildIdentitySha256 = sha(fs.readFileSync(path.join(build, 'build-identity.json')));
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
const check = (name, evidence) => {report.checks.push({name, status: 'PASS', evidence}); save();};
let browser, child, reader;
const requests = new Map();
let requestId = 0;
let readyResolve, readyReject, startupTimer;
const ready = new Promise((resolve, reject) => {readyResolve = resolve; readyReject = reject;});
function owner(command) {
  const id = ++requestId;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {requests.delete(id); reject(new Error('Owner request timeout'));}, 10000);
    requests.set(id, {resolve, reject, timer});
    child.stdin.write(JSON.stringify({...command, id}) + '\n');
  });
}
const html = `<!doctype html><html lang="ur"><meta charset="utf-8"><title>Web input feasibility</title>
<style>@font-face{font-family:ProbeNastaliq;src:url(data:font/ttf;base64,${fs.readFileSync(font).toString('base64')})}
body{margin:32px;background:#edf1f5;color:#172a3a;font:14px system-ui}#editor{background:white;padding:28px;min-height:240px;border:1px solid #ccd6e1;white-space:pre-wrap;outline:none;font:26px ProbeNastaliq;unicode-bidi:plaintext}#other{margin-top:20px}</style>
<h1>Web input feasibility</h1><p>Actual Chromium events; programmatic IME drive. Native OS qualification remains open.</p>
<div id="editor" role="textbox" aria-label="Urdu document experiment" aria-multiline="true" contenteditable="true" dir="rtl"></div>
<button id="other">Other focus target</button>
<script>
 const editor=document.querySelector('#editor');
 window.events=[];window.lease=false;
 function at(node,offset){const r=document.createRange();r.selectNodeContents(editor);r.setEnd(node,offset);return r.toString().length;}
 function selection(){const s=getSelection();return s.anchorNode&&editor.contains(s.anchorNode)?[at(s.anchorNode,s.anchorOffset),at(s.focusNode,s.focusOffset)]:null;}
 function point(index){const t=document.createTreeWalker(editor,NodeFilter.SHOW_TEXT);let n,last;while(n=t.nextNode()){last=n;if(index<=n.data.length)return[n,index];index-=n.data.length;}return[last,last?last.data.length:0];}
 window.selectOffsets=(a,b)=>{const x=point(a),y=point(b);getSelection().setBaseAndExtent(x[0],x[1],y[0],y[1]);};
 window.resetFixture=(text,a,b,bold=false)=>{editor.replaceChildren(document.createTextNode(text.slice(0,a)),Object.assign(document.createElement('span'),{id:'island',textContent:text.slice(a,b)}),document.createTextNode(text.slice(b)));document.querySelector('#island').style.fontWeight=bold?'700':'400';editor.focus();selectOffsets(a,b);events=[];lease=false;};
 for(const type of ['beforeinput','input','compositionstart','compositionupdate','compositionend'])editor.addEventListener(type,e=>{
   if(type==='compositionstart')lease=true;
   events.push({type,data:e.data??null,inputType:e.inputType??null,isComposing:e.isComposing??null,trusted:e.isTrusted,text:editor.textContent,selection:selection(),targets:e.getTargetRanges?[...e.getTargetRanges()].map(r=>[at(r.startContainer,r.startOffset),at(r.endContainer,r.endOffset)]):[]});
   if(type==='compositionend')lease=false;
 });
 window.publishCanonical=(text,protect)=>{if(lease&&protect)return'DEFERRED_PROTECTED';editor.textContent=text;return'REBUILT';};
 // Private flat-body feasibility publisher. The JVM frame remains the authority.
 let publishedOwnerRevision=-1;
 window.publishCanonicalFrame=frame=>{
   if(lease || frame.marked!==null)return'DEFERRED_PROTECTED';
   if(document.activeElement!==editor)return'DEFERRED_UNFOCUSED';
   if(!Number.isSafeInteger(frame.sessionRevision)||frame.sessionRevision<0)throw Error('Invalid owner revision');
   if(frame.sessionRevision<publishedOwnerRevision)return'REJECTED_STALE_FRAME';
   if(frame.units.some(unit=>unit[2]!==0 || unit[4]!==0))return'REJECTED_UNSUPPORTED_FLOW';
   if(frame.units.map(unit=>String.fromCharCode(unit[1])).join('')!==frame.text)throw Error('Frame text mismatch');
   if(frame.selection.length!==2 || frame.selection.some(index=>!Number.isInteger(index)||index<0||index>frame.text.length))throw Error('Invalid owner selection');
   const runs=[];let end=0;
   for(const [a,b,bits] of frame.styles){
     if(a!==end || b<=a || b>frame.text.length || !Number.isInteger(bits) || bits<0 || bits>3)throw Error('Invalid display run');
     const previous=runs[runs.length-1];
     if(previous && previous.bits===bits)previous.end=b;else runs.push({start:a,end:b,bits});
     end=b;
   }
   if(end!==frame.text.length)throw Error('Incomplete display runs');
   const fragment=document.createDocumentFragment();
   for(const run of runs){const span=document.createElement('span');span.textContent=frame.text.slice(run.start,run.end);
     span.style.fontWeight=(run.bits&1)?'700':'400';span.style.fontStyle=(run.bits&2)?'italic':'normal';fragment.append(span);}
   if(!runs.length)fragment.append(document.createTextNode(''));
   editor.replaceChildren(fragment);selectOffsets(...frame.selection);publishedOwnerRevision=frame.sessionRevision;
   return'PUBLISHED';
 };
</script></html>`;

save();
try {
  const cp = path.join(build, 'classes') + path.delimiter + path.join(build, 'lib/*');
  const fontManifest = JSON.parse(fs.readFileSync(path.join(root, 'validation/urdu/u0/fonts.json')));
  assert.equal(report.fontSha256, fontManifest.candidateFonts.find(f => f.fileName === path.basename(font)).sha256);
  report.dependencyLockSha256 = sha(fs.readFileSync(path.join(import.meta.dirname, 'package-lock.json')));
  const command = ['-cp', cp, 'editor.MainKt', '--web-owner-probe'];
  report.ownerCommand = ['java', ...command];
  child = spawn('java', command, {cwd: root, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true});
  child.stderr.on('data', data => fs.appendFileSync(path.join(output, 'owner-stderr.log'), data));
  reader = readline.createInterface({input: child.stdout});
  reader.on('line', line => {
    fs.appendFileSync(path.join(output, 'owner-responses.jsonl'), line + '\n');
    const value = JSON.parse(line);
    if (value.status === 'READY') readyResolve(value);
    else {const pending = requests.get(value.id); if (pending) {clearTimeout(pending.timer); requests.delete(value.id); pending.resolve(value);}}
  });
  child.on('error', error => {readyReject(error); for (const p of requests.values()) {clearTimeout(p.timer); p.reject(error);} requests.clear();});
  child.on('exit', code => {for (const p of requests.values()) {clearTimeout(p.timer); p.reject(new Error('Owner exited: ' + code));} requests.clear();});
  await Promise.race([ready, new Promise((_, reject) => {startupTimer = setTimeout(() => reject(new Error('Owner startup timeout')), 10000);})]);
  clearTimeout(startupTimer);
  browser = await chromium.launch({headless: true});
  report.browserVersion = browser.version();
  report.playwrightVersion = JSON.parse(fs.readFileSync(path.join(import.meta.dirname, 'node_modules/playwright/package.json'))).version;
  const page = await browser.newPage({viewport: {width: 1000, height: 720}});
  await page.setContent(html);
  await page.evaluate(() => document.fonts.load('26px ProbeNastaliq'));
  assert(await page.evaluate(() => document.fonts.check('26px ProbeNastaliq')));
  const cdp = await page.context().newCDPSession(page);
  async function publishOwnerFrame(frame, label) {
    const observed = await page.evaluate(frame => {
      const disposition = publishCanonicalFrame(frame);
      const nodes = [...editor.childNodes].map(node => ({text:node.textContent,
        bits:node.nodeType === Node.ELEMENT_NODE ? (getComputedStyle(node).fontWeight === '700' ? 1 : 0) |
          (getComputedStyle(node).fontStyle === 'italic' ? 2 : 0) : 0}));
      const bits=nodes.flatMap(node=>new Array(node.text.length).fill(node.bits));
      return {disposition, text:editor.textContent, selection:selection(), nodes,bits};
    }, frame);
    fs.writeFileSync(path.join(output, `publication-${label}.json`), JSON.stringify({frame, observed}, null, 2));
    assert.equal(observed.text, frame.text);
    assert.equal(observed.disposition, 'PUBLISHED');
    assert.deepEqual(observed.selection, frame.selection, 'Settled owner publication lost selection');
    const expectedBits=new Array(frame.text.length).fill(0);
    for(const [a,b,bits] of frame.styles)expectedBits.fill(bits,a,b);
    assert.deepEqual(observed.bits,expectedBits,'Settled owner publication lost display attribution');
    const ax=await cdp.send('Accessibility.getFullAXTree');
    const editors=ax.nodes.filter(node=>node.role?.value==='textbox' && node.name?.value==='Urdu document experiment');
    assert.equal(editors.length,1);
    const editorAx=editors[0];
    assert.equal(editorAx.value?.value??'',frame.text,'AX value differs from the owner publication');
    const properties=Object.fromEntries(editorAx.properties.map(property=>[property.name,property.value.value]));
    assert.equal(properties.multiline,true);assert.equal(properties.readonly,false);
    fs.writeFileSync(path.join(output,`accessibility-${label}.json`),JSON.stringify({node:editorAx,scope:'Chromium AX value and editability, not OS screen-reader speech'},null,2));
    assert.deepEqual((await owner({command:'snapshot'})).frame,frame,'Browser publication mutated the owner');
    if(label==='mixed-urdu-backward-selection')await page.screenshot({path:path.join(output,'browser-urdu-publication.png')});
  }

  // Keyboard data is captured from real browser beforeinput/input, not invented operations.
  await page.evaluate(() => resetFixture('اردو ', 5, 5));
  await page.keyboard.type('English 123');
  const keyboard = await page.evaluate(() => ({events, text: editor.textContent}));
  fs.writeFileSync(path.join(output, 'keyboard-events.json'), JSON.stringify(keyboard, null, 2));
  await owner({command: 'reset', text: 'اردو '});
  let pendingInput;
  let typedCount = 0;
  for (const event of keyboard.events) {
    if (event.type === 'beforeinput') pendingInput = event;
    if (event.type !== 'input') continue;
    assert(pendingInput?.inputType === 'insertText' && pendingInput.targets.length === 1);
    const [start, end] = pendingInput.targets[0];
    const response = await owner({command: 'replace', start, end, text: pendingInput.data, beforeText: pendingInput.text});
    assert.equal(response.status, 'Applied'); assert.equal(response.frame.text, event.text); typedCount++;
  }
  assert.equal(keyboard.text, 'اردو English 123');
  assert.equal(typedCount, 11); assert(keyboard.events.filter(e => e.type === 'beforeinput').every(e => e.trusted));
  check('Real Chromium keyboard beforeinput ranges replay exactly through JVM owner', {typedCallbacks: typedCount, text: keyboard.text,
    inputSource: 'DevTools keyboard delivery to actual browser; not OS keyboard layout qualification'});
  const keyboardOwner=(await owner({command:'snapshot'})).frame;
  await publishOwnerFrame(keyboardOwner,'keyboard');

  async function composition(protect,ownerFrame=null) {
    await page.evaluate(() => resetFixture('ABC', 1, 2, true));
    await cdp.send('Input.imeSetComposition', {text: 'にほん', selectionStart: 3, selectionEnd: 3});
    const protection = await page.evaluate(({protect,ownerFrame}) => {
      const island=editor.querySelector('#island');
      const observer=new MutationObserver(()=>{});observer.observe(editor,{childList:true,characterData:true,subtree:true,attributes:true});
      const disposition=publishCanonical('ABC',protect);
      const frameDisposition=ownerFrame?publishCanonicalFrame(ownerFrame):null;
      const mutations=observer.takeRecords().length;observer.disconnect();
      return{disposition,frameDisposition,mutations,originalIslandStillConnected:island?.isConnected??false};
    }, {protect,ownerFrame});
    await cdp.send('Input.imeSetComposition', {text: 'にほ', selectionStart: 2, selectionEnd: 2});
    await cdp.send('Input.insertText', {text: '日本'});
    return {...await page.evaluate(() => ({events, text: editor.textContent, html: editor.innerHTML, selection: getSelection().toString()})), protection};
  }
  const composing = await composition(true,keyboardOwner);
  fs.writeFileSync(path.join(output, 'composition-protected.json'), JSON.stringify(composing, null, 2));
  assert.equal(composing.text, 'A日本C');
  assert.equal(composing.protection.disposition, 'DEFERRED_PROTECTED');
  assert.equal(composing.protection.mutations, 0);
  assert.equal(composing.protection.frameDisposition,'DEFERRED_PROTECTED');
  await owner({command: 'reset', text: 'ABC'});
  await owner({command: 'format', start: 1, end: 2, bold: true});
  await owner({command: 'select', anchor: 1, focus: 2});
  let updates = 0, terminals = 0, epoch;
  let frame = (await owner({command: 'snapshot'})).frame;
  const beforeComposition = structuredClone(frame);
  epoch = frame.epoch;
  for (const event of composing.events) {
    if (event.type === 'compositionupdate') {
      frame = (await owner({command: 'ime', text: event.data, committed: 0, caret: event.data.length, epoch})).frame;
      updates++;
    } else if (event.type === 'compositionend') {
      frame = (await owner({command: 'ime', text: event.data, committed: event.data.length, caret: null, epoch})).frame;
      terminals++;
    } else if (event.type === 'input' && updates > 0) assert.equal(frame.text, event.text);
  }
  assert.equal(terminals, 1); assert(updates >= 2);
  assert.equal(frame.text, composing.text);
  assert.equal(frame.undo, beforeComposition.undo + 1);
  assert(frame.units.slice(1, 3).every(u => u[3] === 1));
  const settled = structuredClone(frame);
  await publishOwnerFrame(settled, 'settled');
  const undone = (await owner({command: 'undo'})).frame;
  assert.equal(undone.text, beforeComposition.text); assert.deepEqual(undone.units, beforeComposition.units); assert.deepEqual(undone.selection, beforeComposition.selection);
  await publishOwnerFrame(undone,'undo');
  const redone=(await owner({command: 'redo'})).frame;
  assert.deepEqual(redone.units, settled.units);
  await publishOwnerFrame(redone,'redo');
  const stalePublication=await page.evaluate(frame=>{
    const observer=new MutationObserver(()=>{});observer.observe(editor,{childList:true,characterData:true,subtree:true,attributes:true});
    const disposition=publishCanonicalFrame(frame), mutations=observer.takeRecords().length;observer.disconnect();return{disposition,mutations,selection:selection()};
  },settled);
  assert.equal(stalePublication.disposition,'REJECTED_STALE_FRAME');assert.equal(stalePublication.mutations,0);assert.deepEqual(stalePublication.selection,redone.selection);
  const stale = await owner({command: 'ime', text: 'late', committed: 4, caret: null, epoch});
  assert.equal(stale.status, 'RejectedStaleEpoch'); assert.deepEqual(stale.frame.units, settled.units);
  check('Protected DOM candidate detours replay actual callback data with exact attribution/history', {updates, terminals, finalText: composing.text, mutationsDuringProtectedPublish: 0, staleStampedCallback: stale.status,
    inputSource: 'CDP drives the real Chromium composition manager; synthetic relative to native OS IME qualification'});
  await page.screenshot({path: path.join(output, 'browser-input.png')});

  const unprotected = await composition(false);
  fs.writeFileSync(path.join(output, 'composition-unprotected.json'), JSON.stringify(unprotected, null, 2));
  report.renderPolicyCounterexample = {finalText: unprotected.text, expected: 'A日本C', mutations: unprotected.protection.mutations,
    committedAtWrongTarget: unprotected.text !== 'A日本C', scope: 'Actual renderer policy changes DOM behavior; not an oracle/snapshot mutation'};
  await owner({command:'reset',text:'کِتاب English 123 😀'});
  await owner({command:'format',start:0,end:2,bold:true});
  await owner({command:'format',start:6,end:13,italic:true});
  const mixed=(await owner({command:'select',anchor:13,focus:2})).frame;
  await publishOwnerFrame(mixed,'mixed-urdu-backward-selection');
  await page.locator('#other').click();
  const focusGuard=await page.evaluate(frame=>{
    const observer=new MutationObserver(()=>{});observer.observe(editor,{childList:true,characterData:true,subtree:true,attributes:true});
    const disposition=publishCanonicalFrame(frame),mutations=observer.takeRecords().length;observer.disconnect();
    return{disposition,mutations,activeElement:document.activeElement.id};
  },mixed);
  fs.writeFileSync(path.join(output,'publication-other-focus.json'),JSON.stringify(focusGuard,null,2));
  assert.equal(focusGuard.disposition,'DEFERRED_UNFOCUSED');assert.equal(focusGuard.mutations,0);assert.equal(focusGuard.activeElement,'other');
  await page.locator('#editor').focus();
  const corpusBytes=fs.readFileSync(path.join(root,'validation/urdu/u0/fixtures.json'));
  report.urduFixtureSha256=sha(corpusBytes);
  const bidiFixtures=JSON.parse(corpusBytes).filter(fixture=>['UTXT-123','UTXT-131'].includes(fixture.id));
  assert.equal(bidiFixtures.length,2);
  for(const fixture of bidiFixtures){
    assert.deepEqual(fixture.text.split('').map(char=>char.charCodeAt(0)),fixture.utf16CodeUnits);
    await owner({command:'reset',text:fixture.text});
    await owner({command:'format',start:0,end:4,bold:true});
    const bidi=(await owner({command:'select',anchor:fixture.text.length-1,focus:1})).frame;
    await publishOwnerFrame(bidi,fixture.id);
  }
  const empty=(await owner({command:'reset',text:''})).frame;
  await publishOwnerFrame(empty,'empty');
  const structural=(await owner({command:'replace',start:0,end:0,text:'اردو\nEnglish',paragraphBreaks:true})).frame;
  const unsupported=await page.evaluate(frame=>{
    const before=editor.innerHTML, selected=selection(),disposition=publishCanonicalFrame(frame);
    return{disposition,unchanged:before===editor.innerHTML && JSON.stringify(selected)===JSON.stringify(selection())};
  },structural);
  assert.equal(unsupported.disposition,'REJECTED_UNSUPPORTED_FLOW');assert(unsupported.unchanged);
  assert.deepEqual((await owner({command:'snapshot'})).frame,structural);
  check('Settled owner frames preserve display attribution, forward/backward selection and exact AX value',{
    publications:['keyboard','settled','undo','redo','mixed-urdu-backward-selection','UTXT-123','UTXT-131','empty'],
    protectedComposition:'ZERO_DOM_MUTATIONS',stalePublication,focusGuard,unsupported,
    scope:'Private flat-body publisher and Chromium AX protocol; no live beforeinput adapter, OS AT or rich-flow claim'});
  report.status = 'BROWSER_OWNER_COMPATIBILITY_PASS_GATE_OPEN';
  report.nativeOsIme = 'NOT RUN'; report.realScreenReader = 'NOT RUN';
  report.unresolved = ['Live semantic-DOM adapter and single authority integration', 'Real Urdu/Japanese OS keyboard/IME traces',
    'Partial commits and terminal ordering across target browsers', 'Rich hierarchy/table/flow projection', 'Native selection/bidi/clipboard/AT qualification'];
  console.log('PASS Web input/owner spike: real Chromium events, CDP composition and protected DOM; native OS qualification OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; console.error(error.stack); process.exitCode = 1;}
finally {
  clearTimeout(startupTimer);
  if (browser) await browser.close();
  if (child && child.exitCode === null) {
    try {await owner({command: 'shutdown'});} catch {}
    child.stdin.end();
    if (child.exitCode === null && child.signalCode === null) {
      let shutdownTimer;
      await Promise.race([new Promise(resolve => child.once('exit', resolve)), new Promise(resolve => {shutdownTimer = setTimeout(() => {child.kill(); resolve();}, 3000);})]);
      clearTimeout(shutdownTimer);
      if (child.exitCode === null && child.signalCode === null) await new Promise(resolve => child.once('exit', resolve));
    }
  }
  reader?.close();
  for (const p of requests.values()) {clearTimeout(p.timer); p.reject(new Error('Probe ended'));} requests.clear();
  report.finishedAt = new Date().toISOString(); report.browserAndOwnerClosed = (!browser || !browser.isConnected()) && (!child || child.exitCode !== null || child.signalCode !== null); save();
}
