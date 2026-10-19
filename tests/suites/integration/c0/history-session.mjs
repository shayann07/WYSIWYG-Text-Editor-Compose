/** Synthetic-clock history control; no OS clock or document oracle is changed. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const output = path.resolve(process.argv.find(a => a.startsWith('--output='))?.slice(9) ?? `desktop-prototype/local-evidence/c0-history-${Date.now()}`);
fs.mkdirSync(output, {recursive: false});
const result = {status: 'RUNNING', gate: 'C0', startedAt: new Date().toISOString(), syntheticElapsedMs: 60_000,
  scope: 'Library history timing experiment; not native IME or production integration'};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(result, null, 2) + '\n');
save();
// lib0 captures Date.now at import. Isolate this injection in its own process,
// then restore the global before testing. Timestamp metadata still uses real Date.
let clock = 1000;
const realNow = Date.now;
Date.now = () => clock;
const Y = await import('yjs');
Date.now = realNow;
try {
  function session(explicitGesture) {
    const doc = new Y.Doc(), body = doc.getArray('body');
    const undo = new Y.UndoManager(body, {trackedOrigins: new Set(['actor-A']), captureTimeout: 500});
    clock += 1000;
    if (explicitGesture) {undo.stopCapturing(); undo.captureTimeout = Number.POSITIVE_INFINITY;}
    doc.transact(() => body.insert(0, [{id: 'A:prefix', unit: 0x06A9, style: 3}]), 'actor-A');
    assert.deepEqual(body.toJSON(), [{id: 'A:prefix', unit: 0x06A9, style: 3}]);
    clock += 60_000;
    doc.transact(() => body.insert(1, [0x062A, 0x0627, 0x0628].map((unit, i) => ({id: `A:suffix:${i}`, unit, style: 3}))), 'actor-A');
    if (explicitGesture) {undo.stopCapturing(); undo.captureTimeout = 500;}
    const groups = undo.undoStack.length, complete = body.toJSON();
    if (explicitGesture) {
      assert.equal(groups, 1);
      undo.undo(); assert.deepEqual(body.toJSON(), []);
      undo.redo(); assert.deepEqual(body.toJSON(), complete);
    } else assert.equal(groups, 2, 'Default-timeout counterexample must reproduce');
    undo.destroy(); doc.destroy(); return {groups, complete};
  }
  result.defaultTimeout = session(false);
  result.explicitSession = session(true);
  result.status = 'HISTORY_SESSION_CONTROL_PASS_GATE_OPEN'; result.c0Qualified = false;
  console.log('PASS C0 history timing: default splits after 60s; explicit session remains one exact undo/redo group; C0 OPEN');
} catch (error) {result.status = 'FAIL'; result.error = error.stack; console.error(error.stack); process.exitCode = 1;}
finally {Date.now = realNow; result.finishedAt = new Date().toISOString(); save();}
