# Extended MCP Server

Extended MCP Server is an IntelliJ IDEA plugin that extends the capabilities of the built-in MCP (Model Context Protocol) server by adding useful tools for working with the project and its dependencies.

## Key Features

- **Search in Dependencies:** Search for text patterns in external libraries and dependencies using regular expressions.
- **Dependency File Inspection:** Provides access to the content of files within project dependencies.
- **Project Synchronization:** A tool to force project synchronization (e.g., Gradle or Maven).

## AI Agent Integration

Enhance your development workflow by connecting this MCP server to AI agents like **Cursor** or **Claude Code**. Providing these agents with deep context from your IDE and its dependencies significantly improves the quality and accuracy of their code suggestions.

For information on how to set up and use the MCP server in IntelliJ IDEA, refer to the [official documentation](https://www.jetbrains.com/help/idea/mcp-server.html#external-client-setup).

## Available Tools (MCP Tools)

The plugin adds the following tools to the MCP server:

### `search_in_dependencies_by_regex`
Searches for text in project libraries using a regular expression.
- **Parameters:**
    - `regexPattern`: The regular expression to search for.
    - `fileMask` (optional): File mask (e.g., `*.java`).
    - `caseSensitive` (optional): Whether to be case-sensitive (default: `true`).
    - `maxUsageCount` (optional): Maximum number of results.

### `get_dependency_file_text`
Returns the text content of a file from dependencies.
- **Parameters:**
    - `url`: File URL (obtained from `search_in_dependencies_by_regex`).
    - `lineNumber`: Line number to center on (1-based).
    - `linesBefore`: Number of lines before the target line.
    - `linesAfter`: Number of lines after the target line.

### `sync_project`
Triggers project synchronization.
