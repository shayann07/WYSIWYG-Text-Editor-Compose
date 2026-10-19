# Rejected vector-outline PDF experiment

This tooling experiment consumes the same captured Compose page plan as the PNG preview. Skia emits actual shaped glyph outlines; PyMuPDF imports those vectors without reshaping or repagination. A separate invisible canonical-text layer attempts to preserve search/copy at the plan's character rectangles. It is not an adopted library backend or a distribution/license decision.

```powershell
python validation/pagination/pdf_outline/experiment.py --pages <page-probe-directory> --output <new-evidence-directory>
```

The script records actual font/source/plan hashes and tool version. It renders every PDF page, compares appearance with the source PNG, checks the image region separately, and independently extracts text/order and character geometry. Thresholds are declared before measurement: <=0.25pt selection-box error, mean pixel error <=3/255, and <=2.5% of pixels differing by more than 32/255 (renderer antialiasing tolerance).

Current verdict is **CANDIDATE_REJECTED_RECORDED**. After preserving SVG namespaces, vector appearance and the resource image match within those raster thresholds. Raw characters can survive, and some searches succeed, but mixed Urdu order/diacritics and selection boxes fail the required checks. Global pixel similarity does not establish image retention, so image crops are checked explicitly. Text multiset/search counts do not establish canonical reading order.

Earlier logical-order/ActualText and directional-matrix candidates are preserved as source snapshots beside their evidence. No failing expected text was normalized or reordered to declare success. The current naive text-layer route has reached its bounded stop condition. A further candidate needs a concrete shaped-glyph/cluster semantic mapping mechanism; do not repeat this approach without one. These PDFs are intermediate diagnostics, not a usable qualified export feature or GP0 completion.

PyMuPDF is used as local experimental tooling only. Production backend licensing, native portability, PDF/UA/tagging, real printing, malformed-text conversion policy, live resource/font arrival and native page-boundary IME remain open.

A subsequent [existing glyph-cluster capability trial](../glyph_clusters/README.md) executes Skiko's RunHandler rather than repeating the hidden-text variants. Glyph data exists, but the tested independent shaping configurations do not all match the actual Compose line plan. It therefore produces no further PDF candidate and does not reopen this rejected route by assumption.
