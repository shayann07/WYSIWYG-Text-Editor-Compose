# GP0 bounded page/output experiment

The integrated verifier runs the Kotlin page experiment and independent raster/XML checks against its fresh build. For an existing identity-verified build:

```powershell
python validation/pagination/gp0/verify.py --build-dir <fresh-build> --output-dir <new-evidence-directory>
```

One plan places three pages of mixed Urdu/English Nastaliq text and diacritics, a generated pinned image, rich two-paragraph cell flows spanning pages, and repeated header/page-number decorations. Every canonical unit is accounted for once. Each fragment paints its original whole paragraph through a clip/translation, preserving paragraph shaping context. The synthetic boundary trace checks accepted prefix, exact surrounding identities/attributes, selection and one-session Undo/Redo. Width reflow is deterministic and presentation-only. This is not the live editable paged surface or a production table schema.

Raster inspection found and repaired white-on-white text in the old U0 specimen renderer. Every U0 specimen now requires non-white ink; a source mutation changing the renderer's paint color to white must fail that assertion.

The existing Skiko runtime lacks the tested PDF Document binding. Its available SVGCanvas bridge omits canonical Urdu letters from text elements. The independent XML check rejects that path before PDF creation. Raster/vector appearance alone cannot establish searchable, selectable logical text. No PDF is produced by this rejected experiment; GP0 remains open. A future backend must consume the same plan and pass independent text/order/geometry checks, alongside live editing, font/resource arrival and available native input/print paths.
