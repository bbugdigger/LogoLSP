package com.bugdigger.logolsp.server

import com.bugdigger.logolsp.analysis.DocumentManager
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.services.TextDocumentService

// Forwards LSP document-lifecycle notifications to the DocumentManager.
// Phase 5 will fill in the per-feature request handlers (definition,
// semanticTokens, prepareRename, rename) on top of this same instance,
// reading from `documents.analysis(uri)`.
class LogoTextDocumentService(private val documents: DocumentManager) : TextDocumentService {

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument
        documents.openOrUpdate(doc.uri, doc.text)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // We advertise Full sync: each change event carries the entire new
        // document text. Take the first (and only) entry.
        val newText = params.contentChanges.firstOrNull()?.text ?: return
        documents.openOrUpdate(params.textDocument.uri, newText)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        documents.close(params.textDocument.uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // No save-time work required: analysis runs on every change.
    }
}
