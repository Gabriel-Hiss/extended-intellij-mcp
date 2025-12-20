package io.github.sushkovpv.extendedmcp.extendedmcpintellij

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import io.github.sushkovpv.extendedmcp.extendedmcpintellij.mcp.ExtendedMcpToolset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.*

private val logger = logger<ExtendedMcpToolWindowFactory>()

class ExtendedMcpToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = ApplicationManager.getApplication().isInternal

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab("Extended MCP", focusOnClickInside = true) {
            ExtendedMcpToolWindowContent(project)
        }
    }
}

@Composable
private fun ExtendedMcpToolWindowContent(project: Project) {
    Column(
        Modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DependencyRegexSearchTool(project)
        DependencyFileViewTool()
        SyncProjectTool(project)
    }
}

@Composable
private fun SyncProjectTool(project: Project) {
    var statusText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toolset = remember { ExtendedMcpToolset() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("sync_project", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !isRunning,
                onClick = {
                    isRunning = true
                    statusText = "Syncing..."
                    scope.launch {
                        val result = runCatching {
                            toolset.syncProject(project)
                        }
                        result.onSuccess {
                            statusText = "Done"
                        }.onFailure { error ->
                            statusText = "Failed: ${error.message}"
                            logger.error(error)
                        }
                        isRunning = false
                    }
                },
            ) { Text("Sync Project") }
            OutlinedButton(
                enabled = !isRunning,
                onClick = {
                    statusText = ""
                },
            ) { Text("Clear") }
        }
        if (statusText.isNotBlank()) {
            Text(statusText)
        }
    }
}

@Composable
private fun DependencyFileViewTool() {
    val urlState = rememberTextFieldState()
    val lineNumberState = rememberTextFieldState("1")
    val linesBeforeState = rememberTextFieldState("300")
    val linesAfterState = rememberTextFieldState("300")
    val outputState = rememberTextFieldState()
    var statusText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toolset = remember { ExtendedMcpToolset() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("get_dependency_file_text", fontWeight = FontWeight.Bold)
        TextField(
            state = urlState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("File URL (from search results)") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                state = lineNumberState,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Line number") },
            )
            TextField(
                state = linesBeforeState,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Lines before") },
            )
            TextField(
                state = linesAfterState,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Lines after") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !isRunning,
                onClick = {
                    val url = urlState.text.toString()
                    val lineNumber = lineNumberState.text.toString().toIntOrNull() ?: 1
                    val linesBefore = linesBeforeState.text.toString().toIntOrNull() ?: 300
                    val linesAfter = linesAfterState.text.toString().toIntOrNull() ?: 300
                    isRunning = true
                    statusText = "Running..."
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.Default) {
                                toolset.get_dependency_file_text(
                                    url,
                                    lineNumber,
                                    linesBefore,
                                    linesAfter
                                )
                            }
                        }
                        result.onSuccess { value ->
                            outputState.setTextAndPlaceCursorAtEnd(value)
                            statusText = "Done"
                        }.onFailure { error ->
                            outputState.setTextAndPlaceCursorAtEnd(error.message ?: "Error")
                            logger.error(error)
                            statusText = "Failed"
                        }
                        isRunning = false
                    }
                },
            ) { Text("Get file text") }
            OutlinedButton(
                enabled = !isRunning,
                onClick = {
                    outputState.setTextAndPlaceCursorAtEnd("")
                    statusText = ""
                },
            ) { Text("Clear") }
        }
        if (statusText.isNotBlank()) {
            Text(statusText)
        }
        TextArea(
            state = outputState,
            modifier = Modifier.fillMaxWidth().height(240.dp),
            readOnly = true,
        )
    }
}

@Composable
private fun DependencyRegexSearchTool(project: Project) {
    val regexState = rememberTextFieldState()
    val fileMaskState = rememberTextFieldState()
    val maxUsageState = rememberTextFieldState("200")
    val outputState = rememberTextFieldState()
    var caseSensitive by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("search_in_dependencies_by_regex", fontWeight = FontWeight.Bold)
        TextField(
            state = regexState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Regex pattern") },
        )
        TextField(
            state = fileMaskState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("File mask (optional), e.g. *.java") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                state = maxUsageState,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Max results") },
            )
            CheckboxRow(
                checked = caseSensitive,
                onCheckedChange = { caseSensitive = it },
                text = "Case-sensitive",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !isRunning,
                onClick = {
                    val regex = regexState.text.toString()
                    val fileMask = fileMaskState.text.toString().trim().ifEmpty { null }
                    val maxUsage = maxUsageState.text.toString().toIntOrNull() ?: 200
                    isRunning = true
                    statusText = "Running..."
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.Default) {
                                ExtendedMcpToolset().searchDependenciesByRegexForProject(
                                    project,
                                    regex,
                                    fileMask,
                                    caseSensitive,
                                    maxUsage,
                                )
                            }
                        }
                        result.onSuccess { value ->
                            val header = buildString {
                                append("Entries: ")
                                append(value.entries.size)
                                append(" | more: ")
                                append(value.probablyHasMoreMatchingEntries)
                                append(" | timedOut: ")
                                append(value.timedOut)
                            }
                            val body = value.entries.joinToString("\n") { entry ->
                                "${entry.fileUrl}:${entry.lineNumber} ${entry.lineText}"
                            }
                            outputState.setTextAndPlaceCursorAtEnd(
                                if (body.isBlank()) header else "$header\n\n$body"
                            )
                            statusText = "Done"
                        }.onFailure { error ->
                            outputState.setTextAndPlaceCursorAtEnd(error.message ?: "Error")
                            thisLogger().error(error)
                            statusText = "Failed"
                        }
                        isRunning = false
                    }
                },
            ) { Text("Run search") }
            OutlinedButton(
                enabled = !isRunning,
                onClick = {
                    outputState.setTextAndPlaceCursorAtEnd("")
                    statusText = ""
                },
            ) { Text("Clear") }
        }
        if (statusText.isNotBlank()) {
            Text(statusText)
        }
        TextArea(
            state = outputState,
            modifier = Modifier.fillMaxWidth().height(240.dp),
            readOnly = true,
        )
    }
}
