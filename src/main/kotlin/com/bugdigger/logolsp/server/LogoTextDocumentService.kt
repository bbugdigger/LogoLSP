package com.bugdigger.logolsp.server

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.services.TextDocumentService

// Stub implementation. Phase 4.3 wires the lifecycle notifications into the
// DocumentManager + Analyzer + diagnostic publication. Phase 5 fills in the
// per-feature request handlers (definition, semanticTokens, prepareRename,
// rename). Until then, the document service does nothing.
class LogoTextDocumentService : TextDocumentService {

    override fun didOpen(params: DidOpenTextDocumentParams) {}
    override fun didChange(params: DidChangeTextDocumentParams) {}
    override fun didClose(params: DidCloseTextDocumentParams) {}
    override fun didSave(params: DidSaveTextDocumentParams) {}
}
