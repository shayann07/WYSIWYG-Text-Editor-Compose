package dev.shayxo.composedoc.androidsample

/** Application controls capture an exact owner/basis/selection before opening a native dialog. */
internal class MobileTarget(private val owners: AndroidOwners) {
    val model = owners.current
    private val basis = model.captureFindBasis()
    private val selection = model.snapshot().selection
    fun requireCurrent() {
        check(owners.current === model) { "Document changed. Cancel and choose the target again." }
        val now = model.captureFindBasis()
        check(now.owner === basis.owner && now.revision == basis.revision && now.document == basis.document) {
            "Content changed. Cancel and choose the target again."
        }
        check(model.snapshot().selection == selection) { "Selection changed. Cancel and choose the target again." }
    }
}
