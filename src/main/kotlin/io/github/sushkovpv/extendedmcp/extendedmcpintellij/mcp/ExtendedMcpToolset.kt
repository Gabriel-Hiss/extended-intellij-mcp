package io.github.sushkovpv.extendedmcp.extendedmcpintellij.mcp

import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.find.impl.IdeaIndexBasedFindInProjectSearchEngine
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.impl.LibraryScopeCache
import com.intellij.openapi.roots.libraries.ui.OrderRoot
import com.intellij.platform.ide.progress.ModalTaskOwner.project
import com.intellij.psi.search.ProjectScope
import kotlinx.coroutines.currentCoroutineContext

/**
 * Simple demo toolset that proves plugin can extend MCP server.
 */
class ExtendedMcpToolset : McpToolset {
    @McpTool
    suspend fun helloFromExtendedMcp(name: String = "world"): String {
        val project = currentCoroutineContext().project

        return "Hello, $name! (from Extended MCP plugin)"
    }
}
