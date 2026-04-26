package com.bugdigger.logolsp.server

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class LogoLanguageServer : LanguageServer, LanguageClientAware {

    private val textDocumentService = LogoTextDocumentService()
    private val workspaceService = LogoWorkspaceService()

    @Volatile
    private var client: LanguageClient? = null

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            // Full document sync for now: the client sends the full new text
            // on every change. Cheap given LOGO programs are tiny; revisit if
            // documents grow large enough to make incremental sync worthwhile.
            setTextDocumentSync(TextDocumentSyncKind.Full)
            // Feature-provider capabilities (definition, semanticTokens,
            // rename) are advertised in Phase 5 as each provider lands.
        }
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun shutdown(): CompletableFuture<Any> {
        // The LSP spec ignores the result; return a non-null placeholder to
        // keep the Kotlin/Java generic interop simple.
        return CompletableFuture.completedFuture(Any())
    }

    override fun exit() {
        // The LSP4J launcher closes the JVM when stdin EOFs.
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        this.client = client
    }
}
