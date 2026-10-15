# A suffix index for forgiving typed-bracket scans

Research result, not a new editor architecture or a completed breakthrough.

The preceding jump-and-resume prototype bounded the number of external openers consumed but left suffix construction uncharged. Its malformed-bracket adversary still forced linear work. This experiment supplies the suffix information directly. It improves the static transfer query and exposes the cost of maintaining that information after edits.

## Exact scope

Input is an ASCII sequence containing three bracket types and neutral characters. An opening pushes its type and identity. A closing finds the nearest matching opening anywhere in the stack, then discards that opening and every opening above it. A closing with no match does nothing. With capacity K, the next opening after the stack fills aborts the scan.

This models a component of Unicode [BD16](https://www.unicode.org/reports/tr9/#BD16). The current specification discards **all** bracket pairs in the isolating run sequence after overflow. The experiment returns an abort indication and no accepted external pairs in that case. A caller would also have to discard earlier pairs from before the supplied suffix. It does not implement Unicode property lookup, equivalent non-ASCII brackets, isolating run construction, internal pair enumeration, N0 directional resolution, the rest of UBA, or glyph layout. External pairs are returned in encounter order; that is not the sorted full BD16 result.

## Recurrence

Let n denote end-of-input, also used as the infinity sentinel. Define the following for every suffix beginning at i, under an initially empty **unbounded** stack:

- J[t,i]: the first unmatched closing bracket of type t, or n.
- H[k,i]: the first input position at which stack depth reaches k, or n.
- F[i]: the final stack as a shared linked list, in bottom-to-top order.

At n, J and H are n and F is empty. A neutral character inherits the next suffix's summaries. A closing of type y also inherits them, except J[y,i] = i.

For an opening of type y, let `m = J[y,i+1]` and `r = m+1` if m is finite, otherwise r = n. Compute:

```text
J[t,i] = J[t,i+1]   if t != y and J[t,i+1] < m
         J[t,r]     otherwise

H[1,i] = i
H[k,i] = H[k-1,i+1] if H[k-1,i+1] < m, for k >= 2
         H[k,r]     otherwise

F[i]   = F[r]                       if m is finite
         prepend((y,i), F[i+1])      otherwise
```

Every dependency has a larger suffix index, so a descending pass computes all rows. Store H only through depth K+1. Store F with shared links rather than copying each stack. The current implementation additionally allocates a depth column only when some suffix reaches that depth. Prepending one opener increases the maximum reached depth by at most one; any earlier, unallocated column is therefore uniformly infinity for the suffixes already processed. Lookup of an absent column returns infinity.

### Why the recurrence holds

Until the first otherwise-unmatched closing y, the new outer opening y remains at the bottom. The internal stack therefore evolves exactly as in the empty scan starting at i+1, with one extra opening underneath. Unmatched closings of other types remain unmatched. At m, closing y discards the outer opening and every still-open internal bracket. The state is then empty and scanning resumes at r. If m does not exist, the outer opening remains below the suffix's final stack. These observations give all three recurrences.

## Applying an arbitrary incoming stack

For incoming stack S at suffix i:

1. Among types present in S, find the earliest J[type,i]. This is the first closing that can consume an incoming opening. Before that event, internal evolution is the empty-suffix evolution above S.
2. Check `H[K - |S| + 1, i]`. If this precedes the closing, abort. This checks transient maximum depth, not merely the final stack length.
3. If there is no closing and no overflow, append F[i] to S and return.
4. Otherwise consume the highest matching incoming opening and all entries above it. Internal openings are also discarded at that closing. Resume at the suffix immediately after it.

Every jump removes at least one incoming entry; at most the original depth d jumps occur. The implementation scans the remaining incoming entries on each jump. Its query cost is O(d² + K), including emitted final-stack entries, independent of text length after indexing. It returns only matches involving incoming openings, not all internal pairs. No exponential table over possible incoming stacks is used.

## What was executed

```powershell
node research/bracket-suffix-index.test.mjs
```

The test compares stack transfers to an independent direct scanner, using exhaustive short strings, random substitutions and incoming stacks, suffix starts, and capacity edges including 62 and 63. It separately inspects every suffix of a random corpus with an unbounded stack to verify J, H, and F directly. A specific test first matches an incoming opening and then overflows, checking that the earlier pair is discarded.

The `[)(]` repetition that defeated the earlier recursive algorithm requires four indexed probes and one jump for an incoming `(` at every tested length from 64 to 65,536. The direct scanner visits every character. Exact counters and a single local build-time sample are retained in `bracket-suffix-index-output.json`. Those timings are not a device benchmark or an editor latency measurement.

## Why this does not yet solve editing

Construction has an O((B+K)n) worst-case time and memory bound, where B is the number of bracket types. The first implementation allocated all depth columns; the 65,536-character case used 18,350,356 bytes for typed arrays alone. That recorded run is in `bracket-suffix-dense-output.json`. The current implementation allocates only reached depth columns; `bracket-suffix-index-output.json` records its memory and counts. JavaScript text/object overhead is additional in both measurements. Deep inputs can still reach the worst-case bound.

The substitution implementation recomputes every row at or before the edit. Editing at positions 0, 32,768 and 65,535 respectively rebuilds 1, 32,769 and 65,536 rows. Cheap suffix queries do not establish cheap updates. Insertion and deletion are not implemented.

An incremental dependency graph could avoid some unchanged work, but that alone supplies no worst-case bound. A local-convergence shortcut is also not automatically sound: a row can depend on a later row beyond its immediate successor through r. The missing result is an efficient dynamic representation of these suffix dependencies that also supports the full observations needed for bidi layout. Rebranding this static table as an editor index would conceal that missing work.

## Dynamic block experiment

`bracket-block-index.mjs` now limits each suffix table to a text block. A pair-at-position query transfers its current stack through each block. It directly scans the block containing the queried character, where it can observe internal pairings involving that character, and uses the suffix index for other blocks. Incoming identities are temporarily mapped to negative IDs and mapped back after transfer. It continues after finding a pair because a later overflow invalidates that earlier result.

Variable-length replacement rebuilds only the blocks containing its endpoints, the replacement text, and at most adjacent blocks needed for occupancy. Blocks are repartitioned evenly. Every block has between floor(L/2) and L characters unless the entire document fits in a single smaller block. This occupancy invariant matters: the initial full-block-plus-remainder scheme admitted chains of tiny blocks under repeated insertion. It was replaced and tested with 1,000 insertions near a block end.

With capacity K and target block size L, the implemented bounds are:

- Small replacement: O(n/L + (B+K)L), including array-block lookup/splicing and index reconstruction.
- Replacement with m inserted characters: O(n/L + (B+K)(L+m)), ignoring deferred garbage-collector work.
- Pair-at-position query: O((n/L)(K²+K) + LK), including incoming-stack copying and the directly scanned block. The final stack is bounded by K unless the query aborts.
- Space: O((B+K)n) worst case, with fewer depth columns on shallow fragments.

For fixed K and B and L chosen proportional to sqrt(n), both small edits and individual pair queries have O(sqrt(n)) operation bounds. The prototype uses an explicit fixed block-size parameter; it does not yet retune that parameter as document size changes substantially. This is ordinary block decomposition. Any original contribution must rest on the suffix-transfer mechanism that makes the block query possible, not on the decomposition itself.

Executed: 183,301 pair-at-position comparisons across 6,400 random variable-length edits, plus malformed/deep fixtures and the fragmentation regression. The oracle is an independent direct scanner of the stated ASCII semantics, not ICU or Unicode's reference implementation. On 65,536 characters with L=256, substitutions at the start, middle, and end each rebuilt 256 characters. Each measured pair query scanned 256 characters and used 257–1,020 table probes across 256 blocks. These counts do not equate a table probe, a stack copy, and a character scan or establish a wall-clock speedup.

The full output is `bracket-block-index-output.json`; the older always-allocated depth-table run is retained as `bracket-block-dense-output.json`. This extension removes the static prototype's full-prefix reconstruction requirement for this query API. It does **not** yet provide per-character bidi levels, viewport geometry, or a rich-text editor architecture.

The depth-adaptive 65,536-character block case allocates 1,841,152 typed-array bytes, compared with 18,420,736 in the saved dense-table run. The standalone suffix index uses 1,835,032 instead of 18,350,356. These improvements apply to the measured shallow-depth fixture; they do not improve the deep-input worst-case bound.

## External oracle cross-check

The unmodified Java paired-bracket reference hosted by Unicode was compiled and executed through a small wrapper in `unicode-oracle`. Its [ReadMe](https://www.unicode.org/Public/PROGRAMS/BidiReferenceJava/ReadMe.txt) declares Unicode 8.0, despite a 2017 file timestamp. It is therefore an external algorithmic cross-check, not certification against the current Unicode version.

The block index matched all pair locations in 1,967 non-overflow fixtures. The run checked 72,490 positions in total, including two overflow fixtures tested against the current discard rule. One intentional discrepancy was observed: on `()` followed by 64 opening parentheses, the old reference retains pair `[0,1]`; the current rule and candidate discard it after overflow at position 65. The other overflow fixture has no earlier pair and consequently no pair-list discrepancy. Source hashes, run instructions, version limits, and exact output are retained in `unicode-oracle/README.md` and `bracket-unicode-oracle-output.json`.

## Prior-art status

Searches for suffix-based forgiving bracket matching and incremental Unicode bracket processing did not identify this exact recurrence. This is a limited search result, not proof of novelty. [Dynamic language membership research](https://www.brics.dk/DS/97/3/BRICS-DS-97-3.pdf) discusses dynamic Dyck-language operations; its standard matching semantics must not be equated with this forgiving scanner without comparison. General memoization, dynamic programming, and parsing reuse are established techniques. No novelty verdict is claimed before comparing the specific recurrence and its useful dynamic extension to those bodies of work.
