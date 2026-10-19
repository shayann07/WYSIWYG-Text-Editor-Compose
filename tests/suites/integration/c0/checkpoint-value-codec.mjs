// Private, versioned C0 tooling experiment. Not a production storage/schema decision.
// Reversible form of durable-authority's existing presence-aware semantic projection.
import assert from 'node:assert/strict';

export const PORTABLE_CHECKPOINT_FORMAT = 'c0-portable-tooling-state-1';
export const MAX_CHECKPOINT_BYTES = 32 * 1024 * 1024;
const MAX_DEPTH = 128, MAX_NODES = 1_000_000;

function budget() {
  let nodes = 0;
  return depth => {assert(depth <= MAX_DEPTH && ++nodes <= MAX_NODES, 'Checkpoint value traversal limit');};
}

export function encodePortableState(state) {
  const visit = budget(), active = new WeakSet();
  function encode(value, depth) {
    visit(depth);
    if (value === undefined) return ['undefined'];
    if (Object.is(value, -0)) return ['negative-zero'];
    if (value === null || typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number') {
      if (typeof value === 'number') assert(Number.isFinite(value), 'Nonfinite checkpoint number');
      return ['value', value];
    }
    assert(typeof value === 'object' && !active.has(value), 'Unsupported or cyclic checkpoint value');
    assert(Object.getOwnPropertySymbols(value).length === 0, 'Symbol checkpoint properties unsupported');
    const descriptors = Object.getOwnPropertyDescriptors(value);
    assert(Object.values(descriptors).every(descriptor => Object.hasOwn(descriptor, 'value')), 'Accessor checkpoint properties unsupported');
    active.add(value);
    try {
      if (Array.isArray(value)) {
        assert(Object.getPrototypeOf(value) === Array.prototype && Object.getOwnPropertyNames(value).length === value.length + 1 &&
          Object.keys(value).length === value.length && Object.keys(value).every((key, i) => key === String(i)), 'Sparse or extended checkpoint array');
        return ['array', value.map(item => encode(item, depth + 1))];
      }
      assert(Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null,
        'Checkpoint projection must contain plain records, not runtime classes or collections');
      assert(Object.values(descriptors).every(descriptor => descriptor.enumerable), 'Nonenumerable checkpoint properties unsupported');
      return ['object', Object.keys(value).sort().map(key => [key, encode(value[key], depth + 1)])];
    } finally {active.delete(value);}
  }
  // Well-formed JSON.stringify escapes lone UTF-16 surrogates instead of UTF-8 replacement.
  const bytes = Buffer.from(JSON.stringify([PORTABLE_CHECKPOINT_FORMAT, encode(state, 0)]), 'utf8');
  assert(bytes.length <= MAX_CHECKPOINT_BYTES, 'Portable checkpoint exceeds bounded payload');
  return bytes;
}

export function decodePortableState(bytes) {
  assert(bytes instanceof Uint8Array && bytes.byteLength <= MAX_CHECKPOINT_BYTES, 'Invalid portable checkpoint bytes/size');
  const envelope = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
  assert(Array.isArray(envelope) && envelope.length === 2 && envelope[0] === PORTABLE_CHECKPOINT_FORMAT, 'Unsupported portable checkpoint version');
  const visit = budget();
  function decode(node, depth) {
    visit(depth);
    assert(Array.isArray(node), 'Invalid tagged checkpoint node');
    const [tag, value] = node;
    if (tag === 'undefined' || tag === 'negative-zero') {
      assert(node.length === 1, 'Invalid singleton checkpoint tag');
      return tag === 'undefined' ? undefined : -0;
    }
    assert(node.length === 2, 'Invalid checkpoint tag arity');
    if (tag === 'value') {
      assert(value === null || ['string', 'boolean'].includes(typeof value) ||
        (typeof value === 'number' && Number.isFinite(value) && !Object.is(value, -0)), 'Invalid checkpoint scalar');
      return value;
    }
    assert(Array.isArray(value), 'Invalid checkpoint container');
    if (tag === 'array') return value.map(item => decode(item, depth + 1));
    assert(tag === 'object', 'Unknown checkpoint value tag');
    const result = {}, keys = new Set();
    for (const entry of value) {
      assert(Array.isArray(entry) && entry.length === 2 && typeof entry[0] === 'string' && !keys.has(entry[0]), 'Invalid/duplicate checkpoint record key');
      keys.add(entry[0]);
      Object.defineProperty(result, entry[0], {value: decode(entry[1], depth + 1), enumerable: true, configurable: true, writable: true});
    }
    return result;
  }
  return decode(envelope[1], 0);
}
