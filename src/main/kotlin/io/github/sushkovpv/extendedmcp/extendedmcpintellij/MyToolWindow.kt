package io.github.sushkovpv.extendedmcp.extendedmcpintellij

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.diagnostic.thisLogger
import io.github.sushkovpv.extendedmcp.extendedmcpintellij.mcp.ExtendedMcpToolset
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab("My Tool Window", focusOnClickInside = true) {
            LaunchedEffect(Unit) {
                // initial data loading
            }

            MyToolWindowContent(project)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MyToolWindowContent(project: Project) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DependencyRegexSearchTool(project)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
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
                                "${entry.filePath}:${entry.lineNumber} ${entry.lineText}"
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
