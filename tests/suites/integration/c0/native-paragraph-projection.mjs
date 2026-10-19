// Private explicit-resynchronization compiler. Never use full replacement for normal typing.
import * as T from '../../codex/run-20261016-20/document-tree.mjs';
import {flow, validate} from './ordered-structure-model.mjs';

export function compileParagraphResynchronization(bundle, flowId, currentLength) {
  const refused = reason => ({outcome: 'UnsupportedProjection', flow: flowId, reason});
  if (!Number.isSafeInteger(currentLength) || currentLength < 0) return refused('Invalid native basis length');
  try {validate(bundle);} catch {return refused('Invalid canonical snapshot');}
  if (typeof bundle.main.id !== 'string' || !bundle.main.id.length) return refused('Invalid canonical document identity');
  let source;
  try {source = flow(bundle, flowId);} catch {return refused('Flow no longer exists');}
  // This harness owner has text/paragraphs and B/I flags. Preserve unsupported
  // canonical structure in the replica/recovery record, rather than flatten it.
  if (source.type !== 'flow' || Object.keys(source.props ?? {}).length || !Array.isArray(source.children) || !source.children.length ||
      source.children.some(node => node.type !== 'paragraph' || node.children || Object.keys(node.props ?? {}).length || Object.keys(node.emptyBlockAttributes ?? {}).length))
    return refused('Requires a qualified richer native structure/paragraph mapping');
  const units = [];
  for (const unit of T.project(source)) {
    if (!['text', 'boundary'].includes(unit.kind) || typeof unit.char !== 'string' || unit.char.length !== 1 ||
        (unit.kind === 'boundary' && unit.char !== '\n')) return refused('Unsupported canonical unit kind');
    const attrs = unit.attrs ?? {};
    if (Object.entries(attrs).some(([key, value]) => !['bold', 'italic'].includes(key) || typeof value !== 'boolean'))
      return refused('Requires a qualified additional attribute mapping');
    const flags = (attrs.bold ? 1 : 0) | (attrs.italic ? 2 : 0), hard = unit.kind === 'boundary';
    // tokenId remains a legacy projection label; it is not a collision-free identity namespace.
    const identity = {domain: hard ? 'paragraph-boundary' : 'text-unit', flow: flowId, id: hard ? unit.rightId : unit.tokenId};
    if (typeof identity.id !== 'string' || !identity.id.length) return refused('Missing canonical identity provenance');
    units.push({tokenId: unit.tokenId, identity, char: unit.char, kind: hard ? 1 : 0, style: hard ? 0 : flags, paragraphStyle: hard ? flags : 0});
  }
  const edits = [{kind: 'replace', start: 0, end: currentLength, text: '', style: 0}];
  for (let start = 0; start < units.length;) {
    const first = units[start]; let end = start + 1;
    while (end < units.length && units[end].kind === first.kind && units[end].style === first.style && units[end].paragraphStyle === first.paragraphStyle) end++;
    edits.push({kind: 'replace', start, end: start, text: units.slice(start, end).map(u => u.char).join(''),
      style: first.kind === 1 ? first.paragraphStyle : first.style, paragraphBreaks: first.kind === 1});
    start = end;
  }
  return {outcome: 'SupportedParagraphProjection', documentId: bundle.main.id, flow: flowId, units, text: units.map(u => u.char).join(''), edits,
    scope: 'Explicit resynchronization of supported B/I paragraphs; not general rich-flow import or stable production identity mapping'};
}
