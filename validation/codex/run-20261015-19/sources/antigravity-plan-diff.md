# Revision 4.13.0 -> 4.14.0

- Fix and reproduce all four reviewed model defects: terminal selection, evolving list ancestry, range shape and text-domain compatibility.
- Correct the AWT getTextLocation rules from JDK 23; retain native hosting as unqualified.
- Add section 4.10 with bounded summary/basis caches, dependency pin/release, queue/target/outcome/history limits and explicit expiry/capacity outcomes.
- Add sessionOrdinal to normalized composition stamps so retired identities can be rejected without an unbounded tombstone set. This does not solve native provenance.
- Remove full snapshots from completed receipts. Preserve only published compact bases for capture and explicit AfterTicket pins; prevent cross-identity native basis reuse.
- Apply terminal native selection before history and queued commands; define Redo-during-composition rejection explicitly.
- Bound private worker caches and expanded pending-command targets; keep outcome delivery capacity sufficient for a settlement batch.
- Preserve prior runs/packages. No production implementation or phase advancement.
