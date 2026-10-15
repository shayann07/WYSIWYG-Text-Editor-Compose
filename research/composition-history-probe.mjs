import assert from 'node:assert/strict';
import {Schema} from './runtime/node_modules/prosemirror-model/dist/index.js';
import {EditorState} from './runtime/node_modules/prosemirror-state/dist/index.js';
import {history, undo, undoDepth} from './runtime/node_modules/prosemirror-history/dist/index.js';

// Explicit model integration policy, NOT a reproduction of the browser's IME.
// Candidate updates are excluded from user history; formatting is included.
// Replacements explicitly inherit bold after the formatting action. The test
// asks whether ordinary mapped history can later undo that originating action.
const schema = new Schema({
  nodes: {doc: {content: 'paragraph+'}, paragraph: {content: 'text*'}, text: {}},
  marks: {bold: {}},
});
const bold = schema.marks.bold.create();
function fresh(text) {
  return EditorState.create({schema,
    doc: schema.node('doc', null, [schema.node('paragraph', null, [schema.text(text)])]),
    plugins: [history()],
  });
}
function marks(state) {
  const result = [];
  state.doc.descendants(node => {
    if (node.isText) for (let i = 0; i < node.text.length; i++) result.push(Boolean(bold.isInSet(node.marks)));
  });
  return result;
}
function candidate(state, from, to, text, inheritBold) {
  const tr = state.tr.replaceWith(1 + from, 1 + to, schema.text(text, inheritBold ? [bold] : []));
  return state.apply(tr.setMeta('addToHistory', false));
}
function revertFormatting(state) {
  let next = state;
  const handled = undo(state, tr => { next = state.apply(tr); });
  return {state: next, handled};
}

const results = [];
for (const scenario of ['no-rewrite-control', 'whole-rewrite', 'partial-commit']) {
  let state = fresh('nihao');
  const snapshotBeforeFormatting = state;
  state = state.apply(state.tr.addMark(1, 6, bold));
  assert.equal(undoDepth(state), 1);
  if (scenario === 'whole-rewrite') state = candidate(state, 0, 5, '你好', true);
  if (scenario === 'partial-commit') {
    // Prefix becomes committed; its successor and the still-live suffix both
    // inherit the previously applied whole-composition format in this policy.
    state = candidate(state, 0, 2, '你', true);
    state = candidate(state, 1, 4, '好', true);
  }
  const before = {text: state.doc.textContent, bold: marks(state), undoDepth: undoDepth(state)};
  const undone = revertFormatting(state);
  const after = {text: undone.state.doc.textContent, bold: marks(undone.state), undoDepth: undoDepth(undone.state)};
  assert.equal(after.text, before.text);
  if (scenario === 'no-rewrite-control') assert.deepEqual(after.bold, [false, false, false, false, false]);
  else {
    assert.deepEqual(after.bold, [true, true]);
    assert.notEqual(snapshotBeforeFormatting.doc.textContent, after.text);
  }
  results.push({scenario, before, after, undoHandled: undone.handled,
    snapshotRollbackText: snapshotBeforeFormatting.doc.textContent});
}

console.log(JSON.stringify({results,
  observed: 'Mapped range history does not undo inherited marks on replacement text in this explicit integration policy. Snapshot rollback changes the final text.',
  scope: 'Actual ProseMirror history 1.5.1 and state/model operations. No browser, native composition, or production integration tested.',
  implication: 'If formatting is intended to follow future candidates, that dependency needs representation beyond a one-time range mark. This is not evidence that a new mechanism has been found.'
}, null, 2));
