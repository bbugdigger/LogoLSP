# Change Signature over LSP — short summary

> Two-paragraph framing of the design. For when you need to mention this in passing — a cover letter, an interview opener, an email reply — without going into the full architecture. Full design at [`change-signature-over-lsp.md`](change-signature-over-lsp.md); interview prep at [`change-signature-talking-points.md`](change-signature-talking-points.md).

---

The internship's broader topic is bringing IntelliJ's **Change Signature** refactoring to the Language Server Protocol. LOGO is small enough that scope-aware Rename was achievable as a single-pass refactoring; Change Signature is fundamentally harder because it needs a multi-field UI — a parameter list with names, types, and defaults; visibility; return type; propagation choices — and **LSP has no native mechanism for rendering that on the client side**.

The design I'd propose builds on the symbol-table-and-`WorkspaceEdit` pipeline already in this codebase, adds three vendor-namespaced JSON-RPC methods (`jetbrains/changeSignature/{prepare, preview, apply}`) discovered through stock `textDocument/codeAction`, and degrades gracefully across three client UX tiers: a native dialog in LSP4IJ (production target), an inputBox sequence for clients with a custom-input extension, and a server-rendered browser form as a universal fallback. Year one ships as a JetBrains-namespaced extension; year two takes it to the LSP working group as a generic *structured-refactoring* capability with Change Signature as the first proof case.
