# Extended MCP Server

Extended MCP Server is a plugin for JetBrains IDEs (IntelliJ IDEA, PyCharm, WebStorm, etc.) that extends the capabilities of the built-in MCP (Model Context
Protocol) server by adding useful tools for working with the project and its dependencies.

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-Extended%20MCP%20Server-blue)](https://plugins.jetbrains.com/plugin/29460-extended-mcp-server/)

## Key Features

- **Search in Dependencies:** Search for text patterns in external libraries and dependencies using regular expressions.
- **Read Dependency File:** Provides access to the content of files within project dependencies.
- **Sync Project:** A tool to update project dependencies (e.g., Gradle or Maven).

## AI Agent Integration

Enhance your development workflow by connecting this MCP server to AI agents like Codex or Claude Code. Providing these
agents with deep context from your IDE and its dependencies significantly improves the quality and accuracy of their
code suggestions.

For information on how to set up and use the MCP server in JetBrains IDEs, refer to
the [official documentation](https://www.jetbrains.com/help/idea/mcp-server.html#external-client-setup).

## Available Tools (MCP Tools)

The plugin adds the following tools to the MCP server:

### `search_in_dependencies_by_regex`

Searches with a regex pattern within the files of project dependencies (libraries) using IntelliJ's search engine.

The result occurrences are surrounded with `||` characters, e.g. `some text ||substring|| text`.

- **Parameters:**
    - `regexPattern`: Regex pattern to search for.
    - `fileMask` (optional): File mask to search for. If not specified, searches for all files. Example: `*.java`.
    - `caseSensitive` (optional): Whether to search for the text in a case-sensitive manner (default: `true`).
    - `maxUsageCount` (optional): Maximum number of entries to return (default: `100`).
    - `timeout` (optional): Timeout in milliseconds (default: `10000`).

### `get_dependency_file_text`

Returns the text content of a dependency file (library). Use the URL returned by `search_in_dependencies_by_regex`.

- **Parameters:**
    - `url`: URL to the file (as returned by `search_in_dependencies_by_regex`).
    - `lineNumber`: Line number to center the view on (1-based).
    - `linesBefore` (optional): Number of lines to show before the target line (default: `30`).
    - `linesAfter` (optional): Number of lines to show after the target line (default: `70`).

### `sync_project`

Synchronizes all external systems (Gradle, Maven, NPM, etc.) and reloads CMake projects in CLion to apply dependency and project model changes.

- **Returns:**
    - Raw output captured from the external system sync as a single string when it fits within the MCP response size.
    - If the output is too large, the plugin saves the full log to a `.log` file in the project root and returns the absolute file path instead.
