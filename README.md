# TACIT — Tracked Agent Capabilities In Types

An [MCP](https://modelcontextprotocol.io/) server that executes Scala 3 code in a sandboxed environment via an embedded REPL. It enforces security through a capability-based system using Scala 3's experimental [capture checking](https://docs.scala-lang.org/scala3/reference/experimental/cc.html), preventing unauthorized access to the file system, processes, and network.

Supports both stateless one-shot execution and stateful sessions that persist definitions across calls.

## Quick Start

```bash
sbt assembly
java -jar target/scala-*/SafeExecMCP-assembly-*.jar
```

To enable execution logging:

```bash
java -jar target/scala-*/SafeExecMCP-assembly-*.jar --record ./log
```

To use a JSON config file:

```bash
java -jar target/scala-*/SafeExecMCP-assembly-*.jar --config config.json
```

## MCP Client Configuration

**Claude Desktop** (`~/.config/claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "scala-exec": {
      "command": "java",
      "args": ["-jar", "/path/to/SafeExecMCP-assembly-*.jar"]
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
      "cwd": "/path/to/TACIT"
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
| `show_interface` | — | Show the full capability API reference |

### Example: Stateful Session

```
1. create_repl_session          → session_id: "abc-123"
2. execute_in_session(code: "val x = 42")   → x: Int = 42
3. execute_in_session(code: "x * 2")        → val res0: Int = 84
4. delete_repl_session(session_id: "abc-123")
```

## Security Model

All user code is validated before execution. Direct use of `java.io`, `java.nio`, `java.net`, `ProcessBuilder`, `Runtime.getRuntime`, reflection APIs, and other unsafe APIs is rejected. Instead, users access these resources through a capability-based API that is automatically injected into every REPL session.

### Capability API

The API exposes three capability request methods, each scoping access to a block:

```scala
// File system — scoped to a root directory
requestFileSystem("/tmp/work") {
  val f = access("data.txt")
  f.write("hello")
  val lines = f.readLines()
  grep("data.txt", "hello")
  find(".", "*.txt")
}

// Process execution — scoped to an allowlist of commands
requestExecPermission(Set("ls", "cat")) {
  val result = exec("ls", List("-la"))
  println(result.stdout)
}

// Network — scoped to an allowlist of hosts
requestNetwork(Set("api.example.com")) {
  val body = httpGet("https://api.example.com/data")
  httpPost("https://api.example.com/submit", """{"key":"value"}""")
}
```

Capabilities cannot escape their scoped block — this is enforced at compile time by Scala 3's capture checker.

### LLM

A secondary LLM is available through the `chat` method — no capability scope required. Safety comes from the `Classified` type system: `chat(String): String` for regular data, `chat(Classified[String]): Classified[String]` for sensitive data.

```scala
// Regular chat
val answer = chat("What is 2 + 2?")

// Classified chat — input and output stay wrapped
requestFileSystem("/secrets") {
  val secret = readClassified("/secrets/key.txt")
  val result = chat(secret.map(s => s"Summarize: $s"))
  // result is Classified[String] — cannot be printed or leaked
}
```

Configure via CLI flags (`--llm-base-url`, `--llm-api-key`, `--llm-model`) or a JSON config file (`--config`). Any OpenAI-compatible API is supported.

### Validation

Code is checked against 40+ forbidden patterns before execution, covering:

- File I/O bypass (`java.io.*`, `java.nio.*`, `scala.io.*`)
- Process bypass (`ProcessBuilder`, `Runtime.getRuntime`, `scala.sys.process`)
- Network bypass (`java.net.*`, `javax.net.*`, `HttpClient`)
- Reflection (`getDeclaredMethod`, `setAccessible`, `Class.forName`)
- JVM internals (`sun.misc.*`, `jdk.internal.*`)
- Capture checking escape (`caps.unsafe`, `unsafeAssumePure`, `.asInstanceOf`)
- System control (`System.exit`, `System.setProperty`, `new Thread`)
- Class loading (`ClassLoader`, `URLClassLoader`)

## Configuration

The server can be configured via CLI flags or a JSON config file.

### CLI Flags

| Flag | Description |
|------|-------------|
| `-r`/`--record <dir>` | Log every execution to disk |
| `-s`/`--strict` | Block file ops (cat, ls, rm, etc.) through exec |
| `--classified-paths <paths>` | Comma-separated classified (protected) paths |
| `-q`/`--quiet` | Suppress startup banner and request/response logging |
| `--no-wrap` | Disable wrapping user code in `def run() = ... ; run()` |
| `--no-session` | Disable session-related tools |
| `-c`/`--config <path>` | JSON config file (flags after `--config` override file values) |
| `--llm-base-url <url>` | LLM API base URL |
| `--llm-api-key <key>` | LLM API key |
| `--llm-model <name>` | LLM model name |

### JSON Config File

```json
{
  "recordPath": "/tmp/recordings",
  "strictMode": true,
  "quiet": false,
  "wrappedCode": false,
  "sessionEnabled": true,
  "classifiedPaths": ["/home/user/secret"],
  "llm": {
    "baseUrl": "https://api.openai.com",
    "apiKey": "sk-...",
    "model": "gpt-4o-mini"
  }
}
```

## Development

```bash
sbt clean                      # Clean build artifacts
sbt compile                    # Compile
sbt test                       # Run all tests
sbt "testOnly *McpServerSuite" # Run a single suite
sbt run                        # Run the server locally
sbt "run --record ./log"       # Run with logging enabled
sbt "run --config config.json" # Run with JSON config
sbt assembly                   # Build fat JAR
```

## Requirements

- JDK 17+
- sbt 1.12+

## License

Apache-2.0
