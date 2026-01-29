# SafeExecMCP

An [MCP](https://modelcontextprotocol.io/) server that executes Scala 3 code via an embedded REPL. Supports both stateless one-shot execution and stateful sessions that persist definitions across calls.

## Quick Start

```bash
sbt assembly
java -jar target/scala-3.8.1/SafeExecMCP-assembly-0.1.0-SNAPSHOT.jar
```

## MCP Client Configuration

**Claude Desktop** (`~/.config/claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "scala-exec": {
      "command": "java",
      "args": ["-jar", "/path/to/SafeExecMCP-assembly-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

<details>
<summary>Using sbt directly (for development)</summary>

```json
{
  "mcpServers": {
    "scala-exec": {
      "command": "sbt",
      "args": ["--error", "run"],
      "cwd": "/path/to/SafeExecMCP"
    }
  }
}
```

</details>

## Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `execute_scala` | `code` | Execute a Scala snippet in a fresh REPL (stateless) |
| `create_repl_session` | — | Create a persistent REPL session, returns `session_id` |
| `execute_in_session` | `session_id`, `code` | Execute code in an existing session (stateful) |
| `list_sessions` | — | List active session IDs |
| `delete_repl_session` | `session_id` | Delete a session |

### Example: Stateful Session

```
1. create_repl_session          → session_id: "abc-123"
2. execute_in_session(code: "val x = 42")   → x: Int = 42
3. execute_in_session(code: "x * 2")        → val res0: Int = 84
4. delete_repl_session(session_id: "abc-123")
```

## Development

```bash
sbt clean                      # Clean build artifacts
sbt compile                    # Compile
sbt test                       # Run all tests
sbt "testOnly *McpServerSuite" # Run a single suite
sbt run                        # Run the server locally
sbt assembly                   # Build fat JAR
```

## Requirements

- JDK 17+
- sbt 1.12+

## License

MIT
