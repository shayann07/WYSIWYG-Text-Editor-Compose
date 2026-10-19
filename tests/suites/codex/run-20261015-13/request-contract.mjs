// Validation model contract. No UI or production runtime dependency.
export function deepFreeze(value) {
  if (value && typeof value === 'object' && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
  return value;
}
export function immutableCopy(value) { return deepFreeze(structuredClone(value)); }

// Exact surviving-content mapping. A native replacement is contiguous: if
// restored/intervening content splits it, reject instead of consuming the gap.
export function mapRange(range, before, after, affinity = 'before') {
  if (!Array.isArray(range) || range.length !== 2 || !range.every(Number.isInteger) ||
      Math.min(...range) < 0 || Math.max(...range) > before.length) throw Error('InvalidTargetRange');
  const [lo, hi] = [Math.min(...range), Math.max(...range)];
  const ranks = new Map(after.map((u, i) => [u.tokenId, i]));
  let result;
  if (lo === hi) {
    // An anchor is the gap between neighboring identities. BEFORE stays before
    // insertions in that gap; AFTER stays after them. BOF/EOF are real sentinels.
    const left = lo === 0 ? -1 : ranks.get(before[lo - 1].tokenId);
    const right = lo === before.length ? after.length : ranks.get(before[lo].tokenId);
    if (left === undefined && right === undefined) throw Error('TargetInvalidated');
    const p = affinity === 'after' ? (right ?? left + 1) : (left === undefined ? right : left + 1);
    result = [p, p];
  } else {
    const positions = before.slice(lo, hi).map(u => ranks.get(u.tokenId));
    if (positions.some(p => p === undefined)) throw Error('TargetInvalidated');
    if (positions.some((p, i) => i > 0 && p !== positions[i - 1] + 1)) throw Error('TargetFragmented');
    result = [positions[0], positions.at(-1) + 1];
  }
  return range[0] <= range[1] ? result : result.reverse();
}

export function mapRequest(payload, before, after) {
  const p = structuredClone(payload);
  const same = before.length === after.length && before.every((u, i) => u.tokenId === after[i].tokenId);
  if (same) return p;
  const oldRange = p.range;
  if (oldRange) p.range = mapRange(oldRange, before, after, p.affinity);
  if (p.target?.type === 'TrackedRange') p.target.range = mapRange(p.target.range, before, after, p.target.affinity);
  if (p.selection) {
    // Native selections accompanying replacement are in the proposed POST-edit
    // coordinate space. Construct that space with stable synthetic identities.
    const replacement = p.type === 'nativeRemoval' ? (p.replacementText ?? '') : p.text;
    if (oldRange && ['preedit', 'commit', 'nativeRemoval'].includes(p.type) && typeof replacement === 'string') {
      const inserted = Array.from({length:replacement.length}, (_, i) => ({tokenId:`proposal:${i}`}));
      const oldPost = [...before.slice(0, oldRange[0]), ...inserted, ...before.slice(oldRange[1])];
      const newPost = [...after.slice(0, p.range[0]), ...inserted, ...after.slice(p.range[1])];
      p.selection = p.selection.map(pos => mapRange([pos,pos], oldPost, newPost, 'after')[0]);
    } else {
      p.selection = p.selection.map(pos => mapRange([pos,pos], before, after, 'after')[0]);
    }
  }
  return p;
}
