# LogoLSP

A Language Server Protocol implementation for the LOGO programming language, written in Kotlin. Built as a JetBrains Java team internship qualifying assignment for the broader project of bringing **Change Signature refactoring to LSP**.

## Features

| Feature | LSP method |
|---|---|
| Syntax highlighting (semantic tokens) | `textDocument/semanticTokens/full` |
| Go-to-definition for procedures and variables | `textDocument/definition` |
| Diagnostics (syntax + semantic) | `textDocument/publishDiagnostics` (server-pushed) |
| Scope-aware Rename refactoring | `textDocument/prepareRename`, `textDocument/rename` |

The diagnostics + rename combo is a deliberate strategic choice: rename exercises the same `WorkspaceEdit` machinery as Change Signature, so this codebase is a small-scale rehearsal of the internship's broader topic.

## Build & run

Requires JDK 17 or newer.

```bash
./gradlew test           # run the test suite (~60 tests across 7 suites)
./gradlew installDist    # build a runnable distribution
```

`installDist` produces:

- `build/install/LogoLSP/bin/LogoLSP` (Unix)
- `build/install/LogoLSP/bin/LogoLSP.bat` (Windows)

The server speaks LSP over **stdio**. It is started by the LSP client; it does not listen on a port.

## Connecting to an LSP client

The recommended client is **[LSP4IJ](https://github.com/redhat-developer/lsp4ij)**, an open-source LSP-client plugin for IntelliJ-based IDEs.

1. Build the server: `./gradlew installDist`.
2. In IntelliJ IDEA: **Settings → Plugins**, install **LSP4IJ**.
3. **Settings → Editor → File Types → Text** → add `*.logo` to the file-name patterns. LSP4IJ keys off IntelliJ's file-type system, so the extension has to be a recognised type before the next step takes effect.
4. **Settings → Languages & Frameworks → Language Servers → New**:
   - **Name:** LogoLSP
   - **Command:** absolute path to `build/install/LogoLSP/bin/LogoLSP` (or `.bat` on Windows)
   - **Mappings → File name patterns:** `*.logo`
5. Open any `.logo` file. Expected behaviour:
   - Keywords, function names, variables, parameters, strings, numbers, and comments are highlighted.
   - **Ctrl-click** on a procedure call jumps to its `to ... end`.
   - **Ctrl-click** on `:foo` jumps to its parameter, `local`, or first `make`.
   - Undefined procedure / undefined variable / wrong arity / redefined procedure show as squiggles with `LOGO00x` codes.
   - **Shift-F6** on a procedure name renames every occurrence in the file.
   - **Shift-F6** on `:foo` inside a procedure that shadows a global renames only the in-scope references — the global stays untouched.
   - **Shift-F6** on a built-in (e.g. `forward`) shows "cannot rename" instead of doing the rename.

Any other generic LSP client should also work — only stdio + the standard methods listed above are required.

## Architecture

```
┌────────────────────────────────────────────────────────────┐
│ Main / LSP4J launcher (stdio)                              │
├────────────────────────────────────────────────────────────┤
│ LogoLanguageServer  (initialize / shutdown / capabilities) │
│  ├─ LogoTextDocumentService                                │
│  └─ LogoWorkspaceService                                   │
├────────────────────────────────────────────────────────────┤
│ DocumentManager:  Map<URI, Analysis>                       │
│   re-analyses on every didOpen / didChange                 │
│   publishes diagnostics after each analysis                │
├────────────────────────────────────────────────────────────┤
│ Analyzer (pure):  text → Lexer → Parser (ANTLR4) → AST     │
│                    → Resolver → SymbolTable + resolution   │
│                    → Diagnostics                           │
├────────────────────────────────────────────────────────────┤
│ Feature providers (read-only over Analysis):               │
│   SemanticTokens · Definition · Rename                     │
└────────────────────────────────────────────────────────────┘
```

`Analysis` is immutable. Feature providers never re-parse — they only query the latest `Analysis` for the requested URI. This makes every provider a pure function and trivially unit-testable without an LSP client.

### Project layout

```
src/main/
├── antlr/com/bugdigger/logolsp/grammar/Logo.g4    # ANTLR4 grammar
└── kotlin/com/bugdigger/logolsp/
    ├── Main.kt                                    # LSP4J launcher
    ├── server/                                    # LSP-facing wiring
    │   ├── LogoLanguageServer.kt
    │   ├── LogoTextDocumentService.kt
    │   └── LogoWorkspaceService.kt
    ├── analysis/                                  # Pure analysis pipeline
    │   ├── Analyzer.kt                            #   orchestrator
    │   ├── DocumentManager.kt                     #   per-doc state + diag publication
    │   ├── Resolver.kt                            #   two-pass declaration + reference linker
    │   ├── ast/                                   #   sealed AST + builder + finder
    │   ├── symbols/                               #   Symbol, Scope, SymbolTable
    │   ├── builtins/                              #   built-in procedure catalog
    │   └── diagnostics/                           #   diagnostic rules + listeners
    └── features/                                  # LSP feature providers
        ├── DefinitionProvider.kt
        ├── SemanticTokensProvider.kt
        └── RenameProvider.kt
```

### The symbol table is the spine

The `Resolver` does two passes:

1. **Declaration pass** — collects every `to ... end` (procedure), every parameter, every hoisted `LOCAL`, and every top-level `make` (global). A procedure can be called before its definition because everything is registered first.
2. **Reference pass** — walks every call site and `:var` reference, looks up the corresponding `Symbol`, links them in a `resolution` map, and appends the reference range to `Symbol.references`.

This means **go-to-definition and rename are both direct lookups, not searches**. A rename is just `symbol.references.map { TextEdit(it, newName) }` — the heavy lifting was already done by the resolver. This is the key payoff of building a real symbol table instead of doing string-based refactoring.

## LOGO dialect & semantics

LOGO is famously underspecified. The reference dialect is **UCB / Berkeley Logo**, restricted to the subset that turtleacademy.com accepts. Resolved ambiguities:

| Topic | Decision |
|---|---|
| Identifier case | Case-insensitive; lower-cased at lex time |
| Procedure form | `to name :p1 :p2 ... <body> end` |
| Variable read | `:name` |
| Quoted word | `"name` (single token, no closing quote) |
| Procedure call | Bare identifier in statement position |
| Comments | `;` to end of line |
| Lists | `[ ... ]` parsed but not evaluated; references inside lists are not tracked |
| Scoping | **Lexical.** `local` introduces a binding visible to the rest of the enclosing procedure. `make "x ...` outside any `local` for `x` introduces or assigns a global. |
| Definition site of `:x` | First `make "x ...` in source order at file scope; or its `local` declaration; or its parameter declaration |
| Procedures | One flat namespace per file; no nested `to` |
| Built-ins | Fixed catalogue (~40 entries). Non-renameable. Go-to-definition returns nothing. |
| Wrong-arity diagnostic | `Warning` (some dialects allow variadic forms; avoid false positives) |
| Multi-file | Each `.logo` file is analysed independently. No cross-file procedure visibility. |
| Redefining a built-in | `Warning`; the redefinition still wins for resolution |

### Diagnostics catalogue

| Code | Severity | Rule |
|---|---|---|
| `LOGO001` | Error | Syntax error (from ANTLR) |
| `LOGO002` | Error | Call to undefined procedure |
| `LOGO003` | Error | Reference to undefined variable |
| `LOGO004` | Warning | Wrong arity (built-in or user procedure) |
| `LOGO005` | Warning | Procedure redefinition (duplicate or shadowing a built-in) |

## Known limitations

- **Nested call results in arguments require parentheses.** The grammar is deliberately context-free, so `forward sum 1 2` does not parse — write `forward (sum 1 2)`. This trade-off keeps the parser simple and explainable.
- **Unary minus and arithmetic in call arguments require parentheses too**, for the same reason: `forward -100` does not parse — write `forward (0 - 100)` or use `back 100`. (Could be relaxed by widening `call : identifier expression*`; out of scope for v1.)
- **No cross-file references.** Standard LOGO has no import mechanism, so each `.logo` file is its own world.
- **Rename does not warn about cross-scope shadowing.** Renaming a global `:x` to `:y` while some procedure already has a local `:y` will silently change which binding the renamed references resolve to inside that procedure. Documented; not flagged.

## Testing

```bash
./gradlew test
```

The suite covers:

- **Parser + AST construction** — sample programs through the full lex→parse→build pipeline, plus range correctness.
- **Resolver** — scope chaining, hoisting, parameter shadowing, reference collection.
- **Diagnostics** — golden-style: one test per `LOGO00x` code.
- **Feature providers** — definition, semantic tokens (with stable legend ordering), prepareRename, rename (including scoped variable rename).
- **Integration** — in-process LSP smoke test driving `initialize → didOpen → definition → prepareRename → rename → shutdown` over piped streams.

## License

Internal — internship submission.
