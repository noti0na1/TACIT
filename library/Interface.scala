package tacit.library

import language.experimental.captureChecking

// ─── Classified Data ────────────────────────────────────────────────────────

/** Wrapper that protects sensitive data from accidental disclosure.
 *
 *  - `toString` never reveals the underlying value (prints `Classified(****)`)
 *  - `map`/`flatMap` only accept **pure** functions, preventing side-channel leaks
 */
trait Classified[+T]:
  def map[B](op: T -> B): Classified[B]
  def flatMap[B](op: T -> Classified[B]): Classified[B]

// ─── File System ────────────────────────────────────────────────────────────

/** Handle to a file or directory, obtained via `access(path)` inside a
 *  `requestFileSystem` block. Cannot escape the block scope. */
abstract class FileEntry(tracked val origin: FileSystem):
  def path: String
  def name: String
  def exists: Boolean
  def isDirectory: Boolean
  def size: Long
  def read(): String
  def readBytes(): Array[Byte]
  def write(content: String): Unit
  def append(content: String): Unit
  def readLines(): List[String]
  def delete(): Unit
  /** List immediate children of a directory. */
  def children: List[FileEntry^{this}]
  /** Recursively list all descendants (files and subdirectories). */
  def walk(): List[FileEntry^{this}]
  /** Whether this file is under a classified (protected) path. */
  def isClassified: Boolean
  /** Read a classified file, returning its content wrapped in [[Classified]].
   *  Throws `SecurityException` if the file is not under a classified path. */
  def readClassified(): Classified[String]
  /** Write classified content to a classified file.
   *  Throws `SecurityException` if the file is not under a classified path. */
  def writeClassified(content: Classified[String]): Unit

/** Capability granting access to a file-system subtree.
 *  Obtained via `requestFileSystem(root)`. */
abstract class FileSystem extends caps.SharedCapability:
  def access(path: String): FileEntry^{this}

// ─── Data Types ─────────────────────────────────────────────────────────────

/** A single match returned by `grep` or `grepRecursive`. */
case class GrepMatch(file: String, lineNumber: Int, line: String)

/** The result of running a process via `exec`. */
case class ProcessResult(exitCode: Int, stdout: String, stderr: String)

// ─── Capabilities ───────────────────────────────────────────────────────────

/** Capability granting access to a set of network hosts.
 *  Obtained via `requestNetwork(hosts)`. */
class Network(val allowedHosts: Set[String]) extends caps.SharedCapability:
  def validateHost(host: String): Unit =
    if !allowedHosts.contains(host) then
      throw SecurityException(
        s"Access denied: host '$host' is not in allowed hosts $allowedHosts"
      )

/** Capability granting permission to run a set of commands.
 *  Obtained via `requestExecPermission(commands)`.
 *  In strict mode, file-operation commands (cat, ls, rm, ...) are also blocked. */
class ProcessPermission(
  val allowedCommands: Set[String],
  val strictMode: Boolean = false
) extends caps.SharedCapability

/** Capability gating access to standard output (`println`, `print`, `printf`).
 *  An implciit instance is available at the REPL top level. */
class IOCapability private extends caps.SharedCapability

// ─── Interface ──────────────────────────────────────────────────────────────

/** The API for interacting with the host system. All the functions are pre-loaded
 *  at the REPL top level.
 *
 *  == Example: basic file operations (write, read, list) ==
 *
 *  {{{
 *  requestFileSystem("/tmp/demo") {
 *    // Access a file or directory via `access()`
 *    val f = access("/tmp/demo/hello.txt")
 *    // Check file metadata
 *    println(s"Name: ${f.name}, Size: ${f.size}, Exists: ${f.exists}")
 *
 *    // Write a file
 *    f.write("Hello, World!\nLine 2")
 *    // Read it back
 *    val content = f.read()
 *    println(s"Content: $content")
 *    // Append to the file
 *    f.append("\nLine 3")
 *    // Read individual lines
 *    val lines = f.readLines()
 *    println(s"Lines: $lines")
 *
 *    // List directory contents
 *    access("/tmp/demo").children.foreach { e =>
 *      println(s"  ${e.name} (dir=${e.isDirectory}, size=${e.size})")
 *    }
 *    // Recursively list all files under the directory
 *    access("/tmp/demo").walk().foreach { e =>
 *      println(s"  ${e.path} (dir=${e.isDirectory}, size=${e.size})")
 *    }
 *  }
 *  }}}
 */
trait Interface:

  // ── File System ─────────────────────────────────────────────────────

  /** Request a [[FileSystem]] scoped to the subtree under `root`.
   *  Paths outside `root` throw `SecurityException`.
   *
   *  {{{
   *  requestFileSystem("/home/user/project") {
   *    val content = access("/home/user/project/README.md").read()
   *    println(content)
   *
   *    access("/home/user/project/out/result.txt").write("done")
   *
   *    access("/home/user/project/src").children.foreach(f => println(f.name))
   *  }
   *  }}} */
  def requestFileSystem[T](root: String)(op: FileSystem^ ?=> T)(using IOCapability): T

  /** Get a [[FileEntry]] handle for `path`. */
  def access(path: String)(using fs: FileSystem): FileEntry^{fs}

  /** Search a single file for lines matching `pattern` (regex).
   *
   *  {{{
   *  val matches = grep("/project/Main.scala", "TODO")
   *  matches.foreach(m => println(s"${m.lineNumber}: ${m.line}"))
   *  }}} */
  def grep(path: String, pattern: String)(using FileSystem): List[GrepMatch]

  /** Recursively search files under `dir` matching `glob` for `pattern` (regex).
   *
   *  {{{
   *  val hits = grepRecursive("/project/src", "deprecated", "*.scala")
   *  hits.foreach(m => println(s"${m.file}:${m.lineNumber}: ${m.line}"))
   *  }}} */
  def grepRecursive(dir: String, pattern: String, glob: String = "*")(using FileSystem): List[GrepMatch]

  /** Find all files under `dir` matching `glob`. Returns absolute paths.
   *
   *  {{{
   *  val files = find("/project/src", "*.scala")
   *  }}} */
  def find(dir: String, glob: String)(using FileSystem): List[String]

  /** Read a classified file. Throws `SecurityException` if the path is not classified.
   *
   *  {{{
   *  val secret: Classified[String] = readClassified("/data/secrets/key.txt")
   *  val processed = secret.map(_.trim.toUpperCase)  // pure transform OK
   *  println(processed)  // prints "Classified(****)" — content protected
   *  }}} */
  def readClassified(path: String)(using FileSystem): Classified[String]

  /** Write classified content to a classified file.
   *
   *  {{{
   *  writeClassified("/data/secrets/upper.txt", processed)
   *  }}} */
  def writeClassified(path: String, content: Classified[String])(using FileSystem): Unit

  // ── Process Execution ───────────────────────────────────────────────

  /** Request a [[ProcessPermission]] for the given command names.
   *
   *  {{{
   *  requestExecPermission(Set("pip", "python")) {
   *    exec("pip", List("install", "."))
   *    execOutput("python", List("script.py"))
   *  }
   *  }}} */
  def requestExecPermission[T](commands: Set[String])(op: ProcessPermission^ ?=> T)(using IOCapability): T

  /** Run `command` with `args`. Returns exit code, stdout, and stderr.
   *  Throws `RuntimeException` on timeout. */
  def exec(
    command: String,
    args: List[String] = List.empty,
    workingDir: Option[String] = None,
    timeoutMs: Long = 30000
  )(using pp: ProcessPermission): ProcessResult

  /** Run `command` and return only stdout. */
  def execOutput(
    command: String,
    args: List[String] = List.empty
  )(using pp: ProcessPermission): String

  // ── Network ─────────────────────────────────────────────────────────

  /** Request a [[Network]] capability for the given host names.
   *
   *  {{{
   *  requestNetwork(Set("api.example.com")) {
   *    val body = httpGet("https://api.example.com/v1/status")
   *    val resp = httpPost("https://api.example.com/v1/data",
   *                        """{"key": "value"}""")
   *  }
   *  }}} */
  def requestNetwork[T](hosts: Set[String])(op: Network^ ?=> T)(using IOCapability): T

  /** HTTP GET. Returns the response body. Host must be in the allowed set. */
  def httpGet(url: String)(using net: Network): String

  /** HTTP POST with `data` as body. Returns the response body. */
  def httpPost(url: String, data: String, contentType: String = "application/json")(using net: Network): String

  // ── print ─────────────────────────────────────────────────────────-

  def println(x: Any)(using IOCapability): Unit
  def println()(using IOCapability): Unit
  def print(x: Any)(using IOCapability): Unit
  def printf(fmt: String, args: Any*)(using IOCapability): Unit

  // ── Classified ──────────────────────────────────────────────────────

  /** Wrap a value in [[Classified]] to protect it from disclosure. */
  def classify[T](value: T): Classified[T]

  // ── LLM ─────────────────────────────────────────────────────────────

  /** Send a message to the configured LLM. No capability scope required.
   *  Throws `RuntimeException` if no LLM is configured.
   *
   *  {{{
   *  val answer = chat("What is the capital of Switzerland?")
   *  }}} */
  def chat(message: String): String

  /** Send a classified message. Returns a classified response.
   *
   *  {{{
   *  val secret = readClassified("/data/secrets/question.txt")
   *  val summary: Classified[String] = chat(secret.map(q => s"Summarize the following: $q"))
   *  }}} */
  def chat(message: Classified[String]): Classified[String]
