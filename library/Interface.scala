package library

import language.experimental.captureChecking

// ─── File System ────────────────────────────────────────────────────────────

/** A handle to a single file or directory obtained from a [[FileSystem]].
 *
 *  `FileEntry` is always tied to the [[FileSystem]] that created it via the
 *  tracked `origin` field, so the compiler's capture checker guarantees it
 *  cannot outlive the scope of `requestFileSystem`.
 *
 *  The entry is lazy: metadata such as `exists` and `size` are read on each
 *  call, and `write` / `append` create parent directories as needed.
 */
abstract class FileEntry(tracked val origin: FileSystem):
  /** Absolute path of this entry. */
  def path: String
  /** File or directory name (last component of the path). */
  def name: String
  /** Whether the file or directory exists. */
  def exists: Boolean
  /** Whether this entry is a directory. */
  def isDirectory: Boolean
  /** Size of the file in bytes (0 for directories). */
  def size: Long
  /** Read the entire file as a UTF-8 string. */
  def read(): String
  /** Read the entire file as raw bytes. */
  def readBytes(): Array[Byte]
  /** Write `content` to the file, creating it if it doesn't exist and
   *  overwriting any previous content. Parent directories are created
   *  automatically. */
  def write(content: String): Unit
  /** Append `content` to the end of the file. */
  def append(content: String): Unit
  /** Read the file and return each line as a list element. */
  def readLines(): List[String]
  /** Delete the file. Throws if the file does not exist. */
  def delete(): Unit
  /** List immediate children of a directory. */
  def children: List[FileEntry^{origin}]
  /** Recursively list all descendants (files and subdirectories). */
  def walk(): List[FileEntry^{origin}]

/** A capability that grants scoped access to a file-system subtree.
 *
 *  `FileSystem` extends `caps.SharedCapability`, which means it can be
 *  shared across closures inside the same `requestFileSystem` block but
 *  the capture checker prevents it from escaping that block. */
abstract class FileSystem extends caps.SharedCapability:
  /** Obtain a [[FileEntry]] for `path`. Throws `SecurityException` if
   *  the path is outside the allowed root. */
  def access(path: String): FileEntry^{this}

// ─── Data types ─────────────────────────────────────────────────────────────

/** A single match returned by `grep` or `grepRecursive`. */
case class GrepMatch(file: String, lineNumber: Int, line: String)

/** The result of running a process via `exec`. */
case class ProcessResult(exitCode: Int, stdout: String, stderr: String)

// ─── Other Capabilities ───────────────────────────────────────────────────────────

/** A capability that grants scoped access to a set of network hosts.
 *
 *  Only hosts in `allowedHosts` may be contacted; any other host causes
 *  a `SecurityException`. Like all capabilities in this library, it
 *  cannot escape the `requestNetwork` block. */
class Network(val allowedHosts: Set[String]) extends caps.SharedCapability:
  def validateHost(host: String): Unit =
    if !allowedHosts.contains(host) then
      throw SecurityException(
        s"Access denied: host '$host' is not in allowed hosts $allowedHosts"
      )

/** A capability that grants scoped permission to run a set of commands.
 *
 *  Only commands in `allowedCommands` may be executed; any other command
 *  causes a `SecurityException`.
 *
 *  In strict mode, file operation commands are also blocked to enforce
 *  proper use of `requestFileSystem` for file access. */
class ProcessPermission(
  val allowedCommands: Set[String],
  val strictMode: Boolean = false
) extends caps.SharedCapability

// ─── Interface ──────────────────────────────────────────────────────────────

/** The main entry point for agents to interact with the host system.
 *
 *  Every operation that touches the outside world (files, processes, network)
 *  requires a corresponding **capability** that is obtained through a
 *  `request*` method. Capabilities are scoped: they exist only inside the
 *  callback and the compiler's capture checker ensures they cannot leak out.
 *
 *  == Capability model ==
 *
 *  There are three capability types, each granted by a `request*` method:
 *
 *   - [[FileSystem]]         — granted by `requestFileSystem`
 *   - [[ProcessPermission]]  — granted by `requestExecPermission`
 *   - [[Network]]            — granted by `requestNetwork`
 *
 *  Once inside a `request*` block the capability is available as a context
 *  parameter (`using`), so helper methods like `access`, `exec`, and
 *  `httpGet` pick it up automatically.
 *
 *  == Quick example ==
 *
 *  {{{
 *  // An interface instance is assumed to be in scope and imported
 *
 *  // Read a file
 *  requestFileSystem("/home/user/project") {
 *    val entry = access("/home/user/project/README.md")
 *    println(entry.read())
 *  }
 *
 *  // Run a command
 *  requestExecPermission(Set("ls", "cat")) {
 *    val result = exec("ls", List("-la"))
 *    println(result.stdout)
 *  }
 *
 *  // Fetch a URL
 *  requestNetwork(Set("api.example.com")) {
 *    val body = httpGet("https://api.example.com/data")
 *    println(body)
 *  }
 *  }}}
 *
 *  == Combining capabilities ==
 *
 *  Blocks can be nested to use multiple capabilities together:
 *
 *  {{{
 *  requestFileSystem("/tmp/out") {
 *    requestNetwork(Set("api.example.com")) {
 *      val data = httpGet("https://api.example.com/data")
 *      access("/tmp/out/result.json").write(data)
 *    }
 *  }
 *  }}}
 *
 *  == Safety guarantees ==
 *
 *  Because capabilities extend `caps.SharedCapability` and the library is
 *  compiled with `-language:experimental.captureChecking`, the following
 *  is rejected at compile time:
 *
 *  {{{
 *  var leaked: FileEntry^ = _
 *  requestFileSystem("/tmp") {
 *    leaked = access("/tmp/secret.txt") // Compile-time error!
 *  }
 *  }}}
 */
trait Interface:

  // ── File system ───────────────────────────────────────────────────────

  /** Request a [[FileSystem]] capability rooted at `root`.
   *
   *  All file operations inside `op` are confined to the subtree under
   *  `root`. Accessing a path outside that subtree throws
   *  `SecurityException`.
   *
   *  {{{
   *  requestFileSystem("/home/user/project") {
   *    val readme = access("/home/user/project/README.md")
   *    println(readme.read())
   *  }
   *  }}} */
  def requestFileSystem[T](root: String)(op: FileSystem^ ?=> T): T

  /** Get a [[FileEntry]] handle for the given absolute path.
   *  Requires a [[FileSystem]] capability in scope. */
  def access(path: String)(using fs: FileSystem): FileEntry^{fs}

  /** Search `path` (a single file) for lines matching `pattern` (regex).
   *  Returns a list of [[GrepMatch]] with file path, 1-based line number,
   *  and the matching line content.
   *
   *  {{{
   *  requestFileSystem("/project") {
   *    val matches = grep("/project/Main.scala", "TODO")
   *    matches.foreach(m => println(s"${m.lineNumber}: ${m.line}"))
   *  }
   *  }}} */
  def grep(path: String, pattern: String)(using fs: FileSystem): List[GrepMatch]

  /** Recursively search all files under `dir` whose names match `glob`
   *  for lines matching `pattern` (regex).
   *
   *  {{{
   *  requestFileSystem("/project") {
   *    val hits = grepRecursive("/project/src", "deprecated", "*.scala")
   *  }
   *  }}} */
  def grepRecursive(dir: String, pattern: String, glob: String = "*")(using fs: FileSystem): List[GrepMatch]

  /** Recursively find all files under `dir` whose names match `glob`.
   *  Returns a list of absolute paths.
   *
   *  {{{
   *  requestFileSystem("/project") {
   *    val scalaFiles = find("/project/src", "*.scala")
   *  }
   *  }}} */
  def find(dir: String, glob: String)(using fs: FileSystem): List[String]

  // ── Process execution ─────────────────────────────────────────────────

  /** Request a [[ProcessPermission]] capability for the given set of
   *  command names.
   *
   *  Only the listed commands may be executed inside `op`; attempting to
   *  run anything else throws `SecurityException`.
   *
   *  {{{
   *  requestExecPermission(Set("ls", "grep")) {
   *    val dirs = exec("ls", List("-la"))
   *    println(dirs.stdout)
   *  }
   *  }}} */
  def requestExecPermission[T](commands: Set[String])(op: ProcessPermission^ ?=> T): T

  /** Execute `command` with `args` and return a [[ProcessResult]] containing
   *  the exit code, stdout, and stderr.
   *
   *  @param command    the executable name (must be in the allowed set)
   *  @param args       command-line arguments
   *  @param workingDir optional working directory
   *  @param timeoutMs  maximum time to wait (default 30 000 ms); throws
   *                    `RuntimeException` on timeout */
  def exec(
    command: String,
    args: List[String] = List.empty,
    workingDir: Option[String] = None,
    timeoutMs: Long = 30000
  )(using pp: ProcessPermission): ProcessResult

  /** Convenience wrapper around `exec` that returns only stdout.
   *
   *  {{{
   *  requestExecPermission(Set("date")) {
   *    val today = execOutput("date")
   *  }
   *  }}} */
  def execOutput(
    command: String,
    args: List[String] = List.empty
  )(using pp: ProcessPermission): String

  // ── Network ───────────────────────────────────────────────────────────

  /** Request a [[Network]] capability for the given set of host names.
   *
   *  Only the listed hosts may be contacted inside `op`; connecting to
   *  any other host throws `SecurityException`.
   *
   *  {{{
   *  requestNetwork(Set("api.example.com")) {
   *    val body = httpGet("https://api.example.com/v1/status")
   *    println(body)
   *  }
   *  }}} */
  def requestNetwork[T](hosts: Set[String])(op: Network^ ?=> T): T

  /** Perform an HTTP GET request and return the response body as a string.
   *  The host in `url` must be in the allowed set. */
  def httpGet(url: String)(using net: Network): String

  /** Perform an HTTP POST request with `data` as the body and return the
   *  response body as a string.
   *
   *  @param url         target URL (host must be allowed)
   *  @param data        request body
   *  @param contentType MIME type of the body (default `application/json`) */
  def httpPost(url: String, data: String, contentType: String = "application/json")(using net: Network): String
