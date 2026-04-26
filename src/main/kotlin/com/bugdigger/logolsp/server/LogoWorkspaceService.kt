package com.bugdigger.logolsp.server

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

// Stub. We currently treat each file as an independent unit and do not react
// to workspace configuration or file-watcher events.
class LogoWorkspaceService : WorkspaceService {

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {}
}
