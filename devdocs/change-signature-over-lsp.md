# Change Signature over LSP — design

> A proposal for bringing IntelliJ IDEA's **Change Signature** refactoring to the Language Server Protocol without sacrificing LSP's "one server, many editors" promise.
>
> Companion design to the [LogoLSP qualifying assignment](../README.md). The LogoLSP work demonstrates the practical foundations (parser, symbol table, scope-aware Rename via `WorkspaceEdit`); this doc addresses the harder, conceptual half of the internship topic.

## 1. Problem statement

IntelliJ IDEA's Change Signature is one of its most-used refactorings. The dialog lets a developer:

- rename a function;
- add, remove, and reorder parameters;
- change parameter types and supply default values for new parameters;
- change the return type, visibility, and `throws` declarations;
- choose how callers are updated — *propagate* the new parameters up the call hierarchy, *delegate* via an overload that calls the new signature, auto-fill new arguments using the *"use any var"* search, etc.

These are choices a user makes interactively in a multi-field, multi-tab dialog before any edit is performed. The refactoring engine then computes a single coherent edit across every call site in the project.

Bringing this to LSP runs into a single hard constraint: **LSP intentionally does not support rich UI on the client side.** The protocol's stock user-input primitives are `window/showMessage` (notify-only) and `window/showMessageRequest` (small set of action buttons) — neither remotely sufficient for a dialog with N parameter rows, dropdowns, and a preview pane. `textDocument/rename` handles the trivial "one new identifier" case; everything beyond that is out of scope for stock LSP.

So the question becomes: **how do we let the IDE host (LSP4IJ inside IntelliJ, in our case) render the existing Change Signature UX while keeping the language analysis on the server, where it belongs, and doing so in a way that other LSP clients can also use the same server?**

## 2. What LSP gives us today

| Primitive | Useful for | Limit |
|---|---|---|
| `textDocument/codeAction` | Surfacing the entry point in any client's refactoring menu | Returns one or more `CodeAction`s with a kind like `refactor.changeSignature`; the heavy work is delegated to a `command` |
| `workspace/executeCommand` | Server-side command invocation triggered by a `CodeAction` | Returns `LSPAny`; what comes back is up to the server |
| `workspace/applyEdit` | Server-driven `WorkspaceEdit` covering many files | This is the right output channel; it's how Rename works today |
| `textDocument/prepareRename` + `textDocument/rename` | Pattern of "validate first, then commit" | Models a *single* string substitution. No multi-field input. |
| `window/showMessageRequest` | Small set of action buttons | Insufficient for a parameter list. Chaining produces awful UX. |
| `window/showDocument` | Open a URL or document on the client | Useful as a **fallback channel** to a browser-based form |
| `experimental` capabilities, custom JSON-RPC methods | **Vendor extensions** | The actual escape hatch for everything LSP doesn't model. Used by [Dart's analyzer](https://github.com/dart-lang/sdk/issues/39842) for exactly this kind of refactoring. |

The relevant prior art is [matklad's "LSP could have been better"](https://matklad.github.io/2023/10/12/lsp-could-have-been-better.html), which observes that LSP's stateless request/response shape is fundamentally mismatched with interactive multi-step refactorings, and [Dart's per-refactoring custom messages](https://github.com/dart-lang/sdk/issues/39842), which demonstrate that the gap can be filled in practice.

## 3. Proposed architecture

A **four-layer** design: discovery via stock LSP, three custom JSON-RPC methods for the protocol extension, three client UX tiers, and capability negotiation to pick the best tier.

### 3.1 Layer 1 — discovery via standard `textDocument/codeAction`

When the cursor is on a function definition or call site, the server returns a `CodeAction`:

```jsonc
{
  "title": "Change signature of computeBalance...",
  "kind": "refactor.changeSignature",
  "command": {
    "title": "Change signature",
    "command": "jetbrains.changeSignature.start",
    "arguments": [{"uri": "...", "position": {...}}]
  }
}
```

This is **stock LSP**. Every client that supports code actions surfaces this in its refactoring menu — VS Code's Quick Fix popup, Neovim's `vim.lsp.buf.code_action()`, IntelliJ's intentions list. Discoverability is universal.

The `command` invocation is the moment we leave standard LSP behind.

### 3.2 Layer 2 — custom JSON-RPC methods

Three new methods, all under a `jetbrains/` namespace so it's clear they're a vendor extension and don't pollute LSP's reserved space.

#### `jetbrains/changeSignature/prepare(textDocument, position) → SignatureDescriptor`

Server returns a structured description of the function under the cursor:

```jsonc
{
  "descriptorVersion": "opaque-token-9f7a3...",   // bumped on document changes
  "language": "java",
  "name": "computeBalance",
  "returnType": "BigDecimal",
  "visibility": "public",
  "modifiers": ["static"],
  "throws": ["IOException"],
  "parameters": [
    { "id": "p0", "name": "userId", "type": "long",    "defaultValue": null, "varargs": false },
    { "id": "p1", "name": "asOf",   "type": "Instant", "defaultValue": null, "varargs": false }
  ],
  "referenceCount": 47,                  // so the UI can warn "47 call sites"
  "callers": null                        // optional Location[]; lazy-loaded on demand
}
```

Two design points:

- **Parameter `id` is opaque and stable.** When the client returns a modified descriptor in step 2 or 3, the server uses ids — not list positions — to tell "this is parameter `p1` renamed and retyped" from "this is a brand-new parameter inserted between `p0` and `p1`". This is what makes reorder-and-rename-in-one-step work correctly.
- **`descriptorVersion` is the cache key.** If the document changes between prepare and apply, the next call rejects with a stale-descriptor error and the client re-prepares. Avoids "applying a refactoring against stale code" classes of bugs.

#### `jetbrains/changeSignature/preview(descriptor, newSignature, options) → PreviewResult`

The server computes the `WorkspaceEdit` for a hypothetical new signature **without applying it**, plus diagnostics for any conflicts:

```jsonc
{
  "edit": <WorkspaceEdit>,
  "diagnostics": [
    {
      "severity": "warning",
      "range": {...},
      "message": "Default value not supplied for new parameter `tax` at call site Foo.java:42"
    }
  ]
}
```

The client renders a preview pane (or the editor's standard "diff to be applied" view) and the user iterates: tweak a parameter type, re-call preview, see updated edit + diagnostics.

#### `jetbrains/changeSignature/apply(descriptor, newSignature, options) → WorkspaceEdit`

Same edit shape as preview, this time final. The client applies it via stock `workspace/applyEdit`. Options control the propagation strategy:

```jsonc
{
  "delegate": false,                      // create overload that calls new sig
  "propagate": ["Foo#bar", "Baz#qux"],    // qualified ids of callers receiving new params
  "searchInComments": false,
  "conflictResolution": "stop"            // "stop" | "skip" | "force"
}
```

### 3.3 Layer 3 — three client UX tiers

The same three protocol methods support three quality levels of client UX. Capability negotiation (§3.4) picks the best available.

#### Tier A — Native dialog *(LSP4IJ + IntelliJ — the production target)*

The IntelliJ host renders the existing Swing Change Signature dialog. The dialog talks to the server via the three custom methods:

```
[user invokes refactoring]
   → LSP4IJ shows native dialog populated from prepare() result
   → user edits → preview() on each material change → diff pane updates
   → user clicks OK → apply() → workspace/applyEdit → done
```

Pixel-identical to today's Change Signature UX. Server is the analysis authority; client owns the rendering. Best UX. **First-class target for LSP4IJ users.**

#### Tier B — InputBox sequence *(generic LSP clients with a rich-input extension)*

For LSP clients that have a custom-extension hook for input boxes (e.g. coc.nvim's `coc#util#input`, or VS Code's `window.showInputBox` / `showQuickPick`), the client extension drives a series of prompts: confirm name → for-each-param: name? type? default? → confirm options → preview → apply.

Slow and error-prone for big signatures, but works *somewhere* without a custom UI. **Reasonable fallback.**

#### Tier C — Browser form *(universal fallback)*

For the long tail of LSP clients with no rich-input extension, the **server itself** drives the UI:

1. Server starts an embedded HTTP server bound to a random localhost port for the duration of the session.
2. On `jetbrains.changeSignature.start`, server picks a one-shot URL and uses `window/showDocument` to open it in the user's default browser.
3. The browser loads a server-rendered HTML form populated from `prepare()`.
4. User edits the form; AJAX calls hit the server's `/preview` endpoint and update an in-page diff view.
5. On submit, the server applies via standard `workspace/applyEdit`, then closes the browser tab.

`window/showDocument` is widely supported; this fallback works with **any LSP client that supports it**. Less elegant than Tier A but completely client-agnostic — exactly the property LSP exists to provide.

### 3.4 Layer 4 — capability negotiation

Both sides advertise what they can do via `experimental` capabilities in `initialize`:

```jsonc
// Server → client (in initialize result)
"capabilities": {
  "experimental": {
    "jetbrains.changeSignature": {
      "version": "1",
      "uiTiers": ["browser"]   // server can drive the browser-form fallback
    }
  }
}

// Client → server (in initialize params)
"capabilities": {
  "experimental": {
    "jetbrains.changeSignature": {
      "version": "1",
      "uiTier": "dialog"   // "dialog" | "inputBox" | "browser"
    }
  }
}
```

Resolution rule: **client wins on UI tier, server gates on protocol version.** If the client doesn't advertise the experimental capability at all, the server omits the `refactor.changeSignature` code action entirely — the feature is invisible, no broken menus, **graceful degradation**.

## 4. Standardisation roadmap

**Year 1 — vendor extension.** Ship as `jetbrains.changeSignature/*`. Prove the architecture in LSP4IJ + a couple of community LSP clients (vim/emacs ecosystems via Tier B/C). Validate the descriptor schema across at least 3 host languages (Java, Kotlin, Python).

**Year 2 — propose to the LSP working group.** Generalise to a **structured-refactoring** capability:

- `textDocument/refactor/prepare(textDocument, position, refactorKind) → RefactorDescriptor`
- `textDocument/refactor/preview(descriptor, modified, options) → PreviewResult`
- `textDocument/refactor/apply(descriptor, modified, options) → WorkspaceEdit`

Each refactoring kind (`changeSignature`, `extractMethod`, `inlineFunction`, ...) registers its own `RefactorDescriptor` schema. Change Signature becomes the proof case; other refactorings follow without re-inventing the protocol.

This is the wider play: **make LSP itself richer**, with Change Signature as the existence proof.

## 5. Why this beats the obvious alternatives

| Alternative | Why it doesn't work |
|---|---|
| "Use webviews" | VS Code-specific. Fails LSP's "one server, all editors" promise out of the gate. |
| "Stream the IntelliJ dialog over the wire" | Couples the server to a specific UI toolkit. Unworkable cross-client. |
| "Decompose into a sequence of `textDocument/rename` calls + manual edits" | Loses propagation, default values, visibility changes, the "delegate" option. Misses ~70% of what the dialog actually does, and the parts it loses are the parts that justify the refactoring. |
| "Wait for LSP to add multi-field input" | Wishful. Even if proposed, it won't ship for years. We need to use what's in the spec today. |
| "VS Code-side extension only" | Doesn't reach the audience JetBrains cares about (every IDE that consumes LSP). |

The recommended approach — **standard `codeAction` for discovery + `experimental` extension methods + tiered fallbacks** — is the only one that both works today and has a credible standardisation path.

## 6. Open questions worth flagging

**Language specifics.** Java visibility (`public`/`protected`/`private`/package) ≠ Kotlin visibility (`public`/`internal`/`protected`/`private`) ≠ Python (none). Varargs syntax, default values, named parameters all differ across languages. The `SignatureDescriptor` carries a `language` field; clients may special-case rendering by language, but the generic renderer must treat unknown fields as opaque text inputs.

**Preview accuracy under document changes.** `descriptorVersion` is the cache key. If the document changes between `prepare` and `apply`, the second call rejects with `staleDescriptor` and the client re-runs prepare. Otherwise we'd risk applying a refactoring computed against text that no longer exists.

**Conflict surfacing.** Renames that collide with existing names, type changes that don't compile, propagation that can't satisfy a caller — surface as `Diagnostic[]` attached to the `PreviewResult`, not as request failures. The user sees them inline and either fixes or chooses `conflictResolution: "force"`.

**Performance for many call sites.** `prepare` returns `referenceCount` only; the full `Location[]` of callers is paginated or fetched on-demand via a separate `jetbrains/changeSignature/listCallers(descriptor, page) → Location[]` call. Avoids "open the dialog, wait 30 seconds for the server to enumerate 5,000 call sites" UX failure mode.

**Multi-step undo.** A single `WorkspaceEdit` is one undo entry by default. For a Change Signature spanning 47 files, that may or may not match user expectation — worth surveying client behaviour in Year 1 before locking it in.

## 7. How this connects to the LogoLSP repo

The LogoLSP work in this repo is a small-scale rehearsal of the relevant pieces:

- The **symbol table + reference list** model (`src/main/kotlin/com/bugdigger/logolsp/analysis/symbols/`) is the same shape Change Signature would need at the bottom of the analysis pipeline — every refactoring is `symbol.references.map { computeEdit(it, change) }`, just with richer change semantics than rename's "swap the text".
- The **prepareRename → rename → WorkspaceEdit** flow (`src/main/kotlin/com/bugdigger/logolsp/features/RenameProvider.kt`) is a working scaled-down version of `prepare → preview → apply`. The progression from one to the other is a richer descriptor and an extra `preview` round trip.
- The **diagnostics pipeline** (`src/main/kotlin/com/bugdigger/logolsp/analysis/diagnostics/`) shows the pattern for surfacing conflicts as `Diagnostic[]` rather than as request errors — directly reusable for the conflict-surfacing decision in §6.

In other words: the Change Signature design isn't a green-field problem. It's the LogoLSP architecture pushed one notch further, with a small custom protocol on top to express what LSP can't.
