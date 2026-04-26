# Change Signature over LSP — interview talking points

> One-page condensed version of [`change-signature-over-lsp.md`](change-signature-over-lsp.md). Designed to be read once before the interview and reproduced from memory. Read the full design first; this is the recall scaffold.

## The 3-sentence pitch

LSP intentionally has no rich UI on the client side, so a multi-field dialog like Change Signature can't be expressed in stock LSP. The fix is a **vendor-namespaced JSON-RPC extension** — `jetbrains/changeSignature/{prepare, preview, apply}` — discovered through stock `textDocument/codeAction`, with **three UX tiers** so universal LSP clients still get *something* (native dialog → inputBox sequence → browser-rendered form). Prove it as a JetBrains extension first; then take it to the LSP working group as a generic *structured-refactoring* capability.

## Architecture in 5 bullets

1. **Discovery is stock LSP.** Server returns `CodeAction(kind: "refactor.changeSignature")` whose `command` invokes the extension protocol.
2. **`prepare(textDocument, position)`** returns a `SignatureDescriptor` — name, parameters (with stable opaque ids, types, defaults), return type, visibility, throws, `referenceCount`, `descriptorVersion`.
3. **Client renders its UI** — Tier A native dialog, Tier B inputBox sequence, or Tier C browser form — populated from the descriptor.
4. **`preview(descriptor, newSignature, options)`** round-trips while the user iterates, returning `{edit, diagnostics}` so the user sees the proposed `WorkspaceEdit` and any conflicts inline.
5. **`apply(descriptor, newSignature, options)`** returns the final `WorkspaceEdit`; client uses standard `workspace/applyEdit`.

## Why three UX tiers

| Tier | Client | UX |
|---|---|---|
| **A — native dialog** | LSP4IJ + IntelliJ | Pixel-identical to today's Change Signature. Production target. |
| **B — inputBox sequence** | Clients with custom-input extensions (coc, VS Code) | Series of prompts: name → for-each-param → options. Slow but works. |
| **C — browser form** | Anything supporting `window/showDocument` | Server starts an embedded HTTP form, opens it in the user's browser. Universal fallback. |

Capability negotiation in `initialize` lets each side advertise what it supports; if the client doesn't advertise the experimental capability, the server omits the code action entirely → graceful degradation.

## Prior art to cite (shows you've researched it)

- **Dart analyzer** has been doing exactly this pattern — custom request/response per refactoring with the IDE hard-coding a dialog per refactoring. Validates the architecture; the gap is that they don't generalise it. See [Dart issue #39842](https://github.com/dart-lang/sdk/issues/39842).
- **matklad's "[LSP could have been better](https://matklad.github.io/2023/10/12/lsp-could-have-been-better.html)"** explicitly calls out the LSP-Change-Signature gap. Shows you read what the rust-analyzer maintainer thinks.
- **LSP 3.17 spec** itself: `experimental` capabilities + custom JSON-RPC are explicitly documented as the extension mechanism. We're not breaking the spec, we're using its escape hatch.

## Three rejected alternatives + 1-line refutations

| Alternative | Refutation |
|---|---|
| **Use webviews** | VS Code-specific — fails LSP's "one server, all editors" promise on day one. |
| **Stream the IntelliJ dialog over the wire** | Couples server to a specific UI toolkit. Cross-client unworkable. |
| **Decompose as a series of renames + edits** | Loses propagation, default values, visibility, the "delegate via overload" option. ~70% of what the dialog does is lost — and those are the parts that justify the refactoring. |

## Three risks to acknowledge (shows you've thought about edges)

1. **Language specifics.** Visibility / varargs / defaults / named-params differ across Java, Kotlin, Python. Solution: `language` field on descriptor; clients render unknown fields as opaque text inputs.
2. **Descriptor staleness.** Document changes between prepare and apply → reject with `staleDescriptor` and force re-prepare. `descriptorVersion` is the cache key.
3. **Conflict surfacing.** Renames that collide with existing names, type changes that don't compile → return as `Diagnostic[]` attached to `PreviewResult`, not as request errors. User fixes inline or chooses `conflictResolution: "force"`.

## Bonus answers if asked

**"What would you actually build first?"** Tier A in LSP4IJ + a Kotlin LSP server that implements `prepare/preview/apply` for one language. Demonstrates the loop end-to-end before generalising.

**"Why not propose to LSP working group first?"** Standardisation without a working implementation goes nowhere. Ship vendor-namespaced, accumulate users, *then* propose with prior-art receipts.

**"How does this connect to your LogoLSP work?"** The symbol-table + reference-list + WorkspaceEdit pipeline I built for Rename is the same pattern, one notch simpler. Change Signature replaces "single new name" with a structured descriptor; everything below that is identical. The LogoLSP repo is a small-scale rehearsal of the foundation.

**"What if the LSP working group says no?"** The vendor-namespaced extension keeps working forever. JetBrains' LSP4IJ users get the feature regardless. Standardisation is a goal, not a gate.

## Closing line

> "And once we have it working in LSP4IJ for one or two languages, we propose it to the LSP working group as a generic `textDocument/refactor/*` capability — Change Signature is the proof case, but extract-method, inline, move-to-class all follow without re-inventing the protocol."
