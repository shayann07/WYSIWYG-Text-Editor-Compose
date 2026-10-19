// Hand-calculated fixtures and regression traces for observable editing contracts (§3, §4, §5)
// All expected outcomes are pre-computed independently before candidate execution.

export const FIXTURES = {
  // -------------------------------------------------------------------------
  // Gap 4.1: Formatting target vs insertion provenance
  // -------------------------------------------------------------------------
  gap41_existing_cat_insert_s: {
    name: 'Gap 4.1: cat -> cats bold targets whole word',
    initialText: 'The cat jumped',
    initialAttrs: [{ bold: true }, { bold: true }, { bold: true }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}],
    steps: [
      { action: 'startComposition', range: [4, 7] }, // "cat"
      { action: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } },
      { action: 'preedit', text: 'cats' }, // inserted "s", visible "The cats jumped"
      { action: 'unmark', settled: true } // settlement completes, formats target
    ],
    expected: {
      finalText: 'The cats jumped',
      // "cats" is at [4, 8) -> all 4 units must be bold
      boldIndices: [0, 1, 2, 4, 5, 6, 7],
      nonBoldIndices: [3, 8, 9, 10, 11, 12, 13],
      undoGroupCount: 2 // 1 Composition group + 1 Formatting group
    }
  },

  gap41_unchanged_candidate_cat: {
    name: 'Gap 4.1: cat -> cat unchanged still formatted',
    initialText: 'The cat jumped',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [4, 7] }, // "cat"
      { action: 'queueCommand', cmd: { type: 'Format', patch: { italic: true } }, target: { type: 'CompositionTarget' } },
      { action: 'preedit', text: 'cat' }, // candidate unchanged
      { action: 'unmark', settled: true }
    ],
    expected: {
      finalText: 'The cat jumped',
      italicIndices: [4, 5, 6],
      nonItalicIndices: [0, 1, 2, 3, 7, 8, 9, 10, 11, 12, 13],
      undoGroupCount: 1 // only Formatting group (Composition had 0 net text diff)
    }
  },

  gap41_combining_mark_grapheme_closure: {
    name: 'Gap 4.1: combining mark insertion expands formatting to grapheme cluster',
    initialText: 'e box', // 'e' at 0..1
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [0, 1] }, // 'e'
      { action: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } },
      { action: 'preedit', text: 'e\u0301' }, // 'e' + acute accent combining mark
      { action: 'unmark', settled: true }
    ],
    expected: {
      finalText: 'e\u0301 box',
      boldIndices: [0, 1], // both base character 'e' and combining mark '\u0301' are bold
      undoGroupCount: 2
    }
  },

  gap41_empty_candidate_outcome: {
    name: 'Gap 4.1: empty candidate outcome updates typingIntent without formatting undo group',
    initialText: 'hello',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [1, 3] }, // "el"
      { action: 'queueCommand', cmd: { type: 'Format', patch: { underline: true } }, target: { type: 'CompositionTarget' } },
      { action: 'preedit', text: '' }, // empty candidate
      { action: 'unmark', settled: true }
    ],
    expected: {
      finalText: 'hlo',
      typingIntent: { underline: true },
      undoGroupCount: 1 // Composition group for deleting "el"; NO empty Formatting group
    }
  },

  gap41_cancelled_composition_command_outcome: {
    name: 'Gap 4.1: cancelled composition terminates command with Cancelled outcome',
    initialText: 'hello',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [1, 3] },
      { action: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } },
      { action: 'preedit', text: 'XYZ' },
      { action: 'cancel' }
    ],
    expected: {
      finalText: 'hello',
      commandOutcome: { status: 'Cancelled', reason: 'CompositionCancelled' },
      undoGroupCount: 0
    }
  },

  gap41_disjoint_retargeting_islands: {
    name: 'Gap 4.1: disjoint retargeting formats constituent islands without convex hull',
    initialText: 'The big bad cat jumped',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [4, 7] }, // "big"
      { action: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } },
      { action: 'targetExpansion', range: [12, 15] }, // disjoint retarget to "cat"
      { action: 'unmark', settled: true }
    ],
    expected: {
      finalText: 'The big bad cat jumped',
      boldIndices: [4, 5, 6, 12, 13, 14], // "big" and "cat" are bold
      nonBoldIndices: [0, 1, 2, 3, 7, 8, 9, 10, 11, 15, 16, 17, 18, 19, 20, 21], // " bad " in convex hull remains UNCHANGED
      undoGroupCount: 1 // only Formatting group
    }
  },

  // -------------------------------------------------------------------------
  // Gap 4.2: Retargeting vs superseding committed content
  // -------------------------------------------------------------------------
  gap42_retargeting_protects_committed: {
    name: 'Gap 4.2: retargeting protects previously committed content against cancellation',
    initialText: 'Hello world',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [1, 2] }, // 'e'
      { action: 'preedit', text: 'XY' }, // "HXyllo world" -> "HXYllo world"
      { action: 'partialCommit', length: 1 }, // commits 'X', markedRange becomes [2, 3) ('Y')
      { action: 'targetExpansion', range: [2, 5] }, // expands markedRange to [2, 5) ('Yll')
      { action: 'cancel' } // cancels uncommitted candidate
    ],
    expected: {
      // 'H' (0..1) + committed 'X' (1..2) + restored 'ello world'[1..] ('ello world' without 'e', so 'llo world')
      // Pre-composition was "Hello world". 'e' became 'XY'. 'X' was committed.
      // Candidate expanded over 'Yll'. Cancellation reverts candidate 'Yll' back to 'll'.
      // Net document is "HXllo world".
      finalText: 'HXllo world',
      undoGroupCount: 1 // exactly 1 Composition group for committed 'X'
    }
  },

  // -------------------------------------------------------------------------
  // Gap 4.3: Partial commits and absorbed original content (Astra Critique 1)
  // -------------------------------------------------------------------------
  gap43_partial_commit_absorbed_content: {
    name: 'Gap 4.3: partial commit commits preceding prefix and preserves absorbed original c',
    initialText: 'abcd',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [1, 2] }, // 'b'
      { action: 'preedit', text: 'XYZ' }, // visible "aXYZcd", overlay covers 'b', units "XYZ"
      { action: 'targetExpansion', range: [1, 5] }, // expand overlay to include 'c' -> "XYZc", range [1, 5)
      { action: 'targetExpansion', range: [2, 3] }, // retarget markedWindow to 'Y' only (range [2, 3) in visible)
      { action: 'partialCommit', length: 1 }, // partially commit marked character 'Y'
      { action: 'cancel' } // cancel remaining provisional work
    ],
    expected: {
      // Committing 'Y' cuts at visible offset 3 (after "XY").
      // Preceding provisional 'X' is also committed.
      // Committed storage Dc becomes "aXYcd" (b replaced by XY).
      // Remaining overlay has 'Z' (provisional) and 'c' (absorbed original).
      // Cancellation drops 'Z' and restores Dc.
      // Original 'c' is preserved!
      finalText: 'aXYcd',
      undoGroupCount: 1
    }
  },

  // -------------------------------------------------------------------------
  // Gap 4.4: Deletions with zero visible length
  // -------------------------------------------------------------------------
  gap44_deletion_at_commit_boundary_cancelled: {
    name: 'Gap 4.4: deletion at commitment cut boundary belongs to remaining effect and is cancelled',
    initialText: 'abc',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [0, 3] }, // "abc"
      { action: 'preedit', text: 'ac' }, // provisionally deletes 'b' (visible "ac")
      { action: 'partialCommit', length: 1 }, // cut after first visible character 'a' (p_cut = 1)
      { action: 'cancel' } // cancel remaining provisional work
    ],
    expected: {
      // Cut is at visible offset 1 (end of 'a').
      // Deletion of 'b' sits at visible offset 1. Because p_cut <= 1, deletion belongs to remaining effect!
      // Committed prefix is only 'a'.
      // Cancelling remainder cancels the deletion of 'b' and provisional 'c', restoring 'b' and 'c'!
      finalText: 'abc',
      undoGroupCount: 0 // net change relative to initial text is 0!
    }
  },

  gap44_deletion_strictly_before_cut_committed: {
    name: 'Gap 4.4: deletion strictly before commit cut is committed',
    initialText: 'abc',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [0, 3] }, // "abc"
      { action: 'preedit', text: 'ac' }, // deletes 'b'
      { action: 'partialCommit', length: 2 }, // cut after 'ac' (p_cut = 2 > 1)
      { action: 'cancel' }
    ],
    expected: {
      // Cut is at visible offset 2, which strictly passes the deletion of 'b' at offset 1.
      // Deletion of 'b' is committed!
      finalText: 'ac',
      undoGroupCount: 1
    }
  },

  gap44_deletion_after_retargeted_cut_boundary: {
    name: 'Gap 4.4: deletion after retargeted cut boundary belongs to remaining effect and is cancelled',
    initialText: 'abcde',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [1, 4] }, // "bcd"
      { action: 'preedit', text: 'bd' }, // provisionally deletes 'c'
      { action: 'targetExpansion', range: [1, 2] }, // retarget 'b' only
      { action: 'partialCommit', length: 1 }, // commit 'b' only (cut at visible offset 1, before deletion of 'c' at offset 2)
      { action: 'cancel' } // cancel remaining provisional work
    ],
    expected: {
      finalText: 'abcde', // original 'c' must survive!
      undoGroupCount: 0
    }
  },

  // -------------------------------------------------------------------------
  // Gap 4.5: Replacement rollback ownership
  // -------------------------------------------------------------------------
  gap45_replacement_rollback_ownership: {
    name: 'Gap 4.5: replacement rollback obligation has exactly one owner when split',
    initialText: 'abc',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [1, 2] }, // 'b'
      { action: 'preedit', text: 'XYZ' }, // visible "aXYZc"
      { action: 'targetExpansion', range: [2, 3] }, // retarget interior 'Y'
      { action: 'preedit', text: 'UVW' }, // replace 'Y' with 'UVW' -> visible "aXUVWZc"
      { action: 'partialCommit', length: 1 }, // commits 'U' (along with preceding 'X' -> "XU")
      { action: 'cancel' } // cancel remaining 'VWZ'
    ],
    expected: {
      // Committed storage has 'a' + committed replacement prefix "XU" + original 'c' -> "aXUc".
      // Rollback obligation to 'b' was satisfied by the partial commit of the replacement.
      // Cancellation of remaining units 'VWZ' drops them cleanly without duplicating or losing 'b'.
      finalText: 'aXUc',
      undoGroupCount: 1
    }
  },

  // -------------------------------------------------------------------------
  // Gap 4.6: Attribution and identity detour
  // -------------------------------------------------------------------------
  gap46_attribution_detour_and_suffix_epoch: {
    name: 'Gap 4.6: detour ab -> x -> ab preserves constituent bold/italic; suffix epoch preserves suffix attrs',
    initialText: 'ab',
    initialAttrs: [{ bold: true }, { italic: true }],
    steps: [
      { action: 'startComposition', range: [0, 2] }, // "ab"
      { action: 'preedit', text: 'x' }, // detour to 'x'
      { action: 'preedit', text: 'ab' }, // back to 'ab' -> restored bold/italic!
      { action: 'partialCommit', length: 1 }, // freeze bold 'a', suffix epoch starts for italic 'b'
      { action: 'preedit', text: 'y' }, // suffix detour
      { action: 'preedit', text: 'b' }, // suffix restored
      { action: 'unmark', settled: true }
    ],
    expected: {
      finalText: 'ab',
      attrs: [{ bold: true }, { italic: true }],
      undoGroupCount: 0 // net text and attributes identical to initial
    }
  },

  // -------------------------------------------------------------------------
  // Gap 4.7: Settlement and lifecycle
  // -------------------------------------------------------------------------
  gap47_settlement_token_lifecycle: {
    name: 'Gap 4.7: settlement token invalidated on new input, guards prevent stale completion',
    initialText: 'hello',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [1, 3] }, // "el"
      { action: 'preedit', text: 'XY' },
      { action: 'unmark', settled: false }, // nonterminal unmark -> AwaitingSettlement, token T1
      { action: 'preedit', text: 'XYZ', range: [1, 3] }, // explicit retargeting replaces previous candidate and invalidates T1
      { action: 'unmark', settled: false }, // new unmark -> token T2
      { action: 'completeSettlement', token: 'T1', shouldReject: true, expectedReason: 'ObsoleteSettlementToken' },
      { action: 'completeSettlement', useActiveToken: true } // completes with valid T2
    ],
    expected: {
      finalText: 'hXYZlo',
      undoGroupCount: 1
    }
  },

  // -------------------------------------------------------------------------
  // Hand-Calculated Regressions for Contract Violations A-D
  // -------------------------------------------------------------------------
  finding_a_repeated_undo_redo_empty_preimage: {
    name: 'Finding A: repeated undo/redo preserves empty document preimage',
    initialText: '',
    initialAttrs: null,
    steps: [
      { action: 'commit', text: 'x' },
      { action: 'undo' },
      { action: 'redo' },
      { action: 'undo' }
    ],
    expected: {
      finalText: '',
      undoGroupCount: 0
    }
  },

  finding_b_native_removal_history: {
    name: 'Finding B: native removal creates tracked undo history',
    initialText: 'abc',
    initialAttrs: null,
    steps: [
      { action: 'nativeRemoval', range: [1, 2], replacementText: '' },
      { action: 'undo' }
    ],
    expected: {
      finalText: 'abc',
      undoGroupCount: 0
    }
  },

  finding_c_disposal_preserves_provisional: {
    name: 'Finding C: disposal preserves accepted provisional content',
    initialText: 'Hello',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [1, 2] },
      { action: 'preedit', text: 'X' },
      { action: 'dispose' }
    ],
    expected: {
      finalText: 'HXllo',
      undoGroupCount: 1
    }
  },

  finding_d_resumed_preedit_omitted_range_selection: {
    name: 'Finding D: resumed preedit with omitted range targets selection',
    initialText: 'Hello',
    initialAttrs: null,
    steps: [
      { action: 'startComposition', range: [1, 2] },
      { action: 'preedit', text: 'X' },
      { action: 'unmark', settled: false, selection: [2, 2] },
      { action: 'preedit', text: 'Y' }, // omitted range targets selection [2, 2]
      { action: 'unmark', settled: true }
    ],
    expected: {
      finalText: 'HXYllo',
      undoGroupCount: 1
    }
  }
};
