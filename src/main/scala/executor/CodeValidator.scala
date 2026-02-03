package executor

import scala.util.matching.Regex

/** A violation found by the code validator. */
case class ValidationViolation(
    ruleId: String,
    description: String,
    lineNumber: Int,
    snippet: String
)

/** Validates user code against forbidden patterns before REPL execution. */
object CodeValidator:

  private case class ForbiddenPattern(
      id: String,
      regex: Regex,
      description: String
  )

  private val forbiddenPatterns: List[ForbiddenPattern] = List(
    // File IO bypass
    ForbiddenPattern("file-io-java", raw"java\.io\b".r, "Direct java.io access is forbidden; use requestFileSystem"),
    ForbiddenPattern("file-io-nio", raw"java\.nio\b".r, "Direct java.nio access is forbidden; use requestFileSystem"),
    ForbiddenPattern("file-io-scala", raw"scala\.io\b".r, "Direct scala.io access is forbidden; use requestFileSystem"),

    // Process bypass
    ForbiddenPattern("proc-builder", raw"ProcessBuilder".r, "ProcessBuilder is forbidden; use requestExecPermission"),
    ForbiddenPattern("proc-runtime", raw"Runtime\.getRuntime".r, "Runtime.getRuntime is forbidden; use requestExecPermission"),
    ForbiddenPattern("proc-scala", raw"scala\.sys\.process".r, "scala.sys.process is forbidden; use requestExecPermission"),

    // Network bypass
    ForbiddenPattern("net-java", raw"java\.net\b".r, "Direct java.net access is forbidden; use requestNetwork"),
    ForbiddenPattern("net-javax", raw"javax\.net\b".r, "Direct javax.net access is forbidden; use requestNetwork"),
    ForbiddenPattern("net-http-client", raw"HttpClient".r, "HttpClient is forbidden; use requestNetwork"),
    ForbiddenPattern("net-http-conn", raw"HttpURLConnection".r, "HttpURLConnection is forbidden; use requestNetwork"),

    // Cast escape
    ForbiddenPattern("cast-escape", raw"\.asInstanceOf\[".r, ".asInstanceOf is forbidden"),

    // CC unsafe
    ForbiddenPattern("cc-unsafe-caps", raw"caps\.unsafe".r, "caps.unsafe explicitly escapes capture checking"),
    ForbiddenPattern("cc-unsafe-pure", raw"unsafeAssumePure".r, "unsafeAssumePure explicitly escapes capture checking"),

    // Reflection
    ForbiddenPattern("reflect-method", raw"getDeclaredMethod".r, "Reflective access is forbidden"),
    ForbiddenPattern("reflect-field", raw"getDeclaredField".r, "Reflective access is forbidden"),
    ForbiddenPattern("reflect-ctor", raw"getDeclaredConstructor".r, "Reflective access is forbidden"),
    ForbiddenPattern("reflect-accessible", raw"setAccessible".r, "Reflective access is forbidden"),
    ForbiddenPattern("reflect-java", raw"java\.lang\.reflect\b".r, "java.lang.reflect is forbidden"),
    ForbiddenPattern("reflect-scala", raw"scala\.reflect\.runtime".r, "scala.reflect.runtime is forbidden"),
    ForbiddenPattern("reflect-forname", raw"Class\.forName".r, "Class.forName is forbidden"),

    // JVM internals
    ForbiddenPattern("jvm-sun-misc", raw"sun\.misc\b".r, "sun.misc access is forbidden"),
    ForbiddenPattern("jvm-jdk-internal", raw"jdk\.internal\b".r, "jdk.internal access is forbidden"),
    ForbiddenPattern("jvm-sun", raw"\bsun\.\w+".r, "sun.* access is forbidden"),
    ForbiddenPattern("jvm-com-sun", raw"com\.sun\.\w+".r, "com.sun.* access is forbidden"),

    // System control
    ForbiddenPattern("sys-exit", raw"System\.exit".r, "System.exit is forbidden"),
    ForbiddenPattern("sys-setprop", raw"System\.setProperty".r, "System.setProperty is forbidden"),
    ForbiddenPattern("sys-getenv", raw"System\.getenv".r, "System.getenv is forbidden"),
    ForbiddenPattern("sys-getprop", raw"System\.getProperty".r, "System.getProperty is forbidden"),
    ForbiddenPattern("sys-thread", raw"\bnew\s+Thread\b".r, "Creating threads is forbidden"),

    // Directives
    ForbiddenPattern("directive-using", raw"//>\s*using".r, "//> using directives are forbidden"),
    ForbiddenPattern("directive-import", """import\s+\$""".r, "import $ directives are forbidden"),

    // Class loading
    ForbiddenPattern("classloader", raw"ClassLoader".r, "ClassLoader access is forbidden"),
    ForbiddenPattern("urlclassloader", raw"URLClassLoader".r, "URLClassLoader access is forbidden"),
    ForbiddenPattern("dotty-tools", raw"dotty\.tools\b".r, "dotty.tools access is forbidden"),
    ForbiddenPattern("scala-tools", raw"scala\.tools\b".r, "scala.tools access is forbidden"),
  )

  /** Strip string literals and comments, replacing their content with spaces.
    * Preserves newlines so line numbers remain correct.
    */
  def stripLiteralsAndComments(code: String): String =
    val sb = new StringBuilder(code.length)
    var i = 0
    val len = code.length

    while i < len do
      if i + 2 < len && code.substring(i, i + 3) == "\"\"\"" then
        // Triple-quoted string
        sb.append("   ") // replace opening """
        i += 3
        var closed = false
        while i < len && !closed do
          if i + 2 < len && code.substring(i, i + 3) == "\"\"\"" then
            sb.append("   ") // replace closing """
            i += 3
            closed = true
          else
            if code.charAt(i) == '\n' then sb.append('\n')
            else sb.append(' ')
            i += 1
      else if code.charAt(i) == '"' then
        // Regular string
        sb.append(' ') // replace opening "
        i += 1
        var closed = false
        while i < len && !closed do
          if code.charAt(i) == '\\' && i + 1 < len then
            sb.append("  ") // replace escape sequence
            i += 2
          else if code.charAt(i) == '"' then
            sb.append(' ') // replace closing "
            i += 1
            closed = true
          else if code.charAt(i) == '\n' then
            sb.append('\n')
            i += 1
          else
            sb.append(' ')
            i += 1
      else if i + 1 < len && code.charAt(i) == '/' && code.charAt(i + 1) == '/' then
        // Line comment — but check for //> using directive first
        // We still need to strip the comment content, the pattern matching
        // runs against the stripped version. The directive pattern won't match
        // after stripping. So we handle directives specially:
        // Actually, //> using IS a comment and should be blocked. We need
        // the pattern to match, so we preserve the comment prefix "//>" for
        // directive detection but blank the rest.
        // Simpler: just blank the whole comment. The directive check should
        // run on the original code. Let's keep it simple and blank all comments.
        while i < len && code.charAt(i) != '\n' do
          sb.append(' ')
          i += 1
      else if i + 1 < len && code.charAt(i) == '/' && code.charAt(i + 1) == '*' then
        // Block comment
        sb.append("  ") // replace /*
        i += 2
        var closed = false
        while i < len && !closed do
          if i + 1 < len && code.charAt(i) == '*' && code.charAt(i + 1) == '/' then
            sb.append("  ") // replace */
            i += 2
            closed = true
          else
            if code.charAt(i) == '\n' then sb.append('\n')
            else sb.append(' ')
            i += 1
      else
        sb.append(code.charAt(i))
        i += 1

    sb.toString

  /** Patterns that must be checked against the original (unstripped) code. */
  private val originalCodePatterns: Set[String] = Set("directive-using", "directive-import")

  /** Validate code against all forbidden patterns.
    * Returns Right(code) if valid, Left(violations) if forbidden patterns found.
    */
  def validate(code: String): Either[List[ValidationViolation], String] =
    val stripped = stripLiteralsAndComments(code)
    val originalLines = code.linesIterator.toArray
    val strippedLines = stripped.linesIterator.toArray

    val violations = for
      pattern <- forbiddenPatterns
      lines = if originalCodePatterns.contains(pattern.id) then originalLines else strippedLines
      (line, idx) <- lines.zipWithIndex
      if pattern.regex.findFirstIn(line).isDefined
    yield ValidationViolation(
      ruleId = pattern.id,
      description = pattern.description,
      lineNumber = idx + 1,
      snippet = originalLines.lift(idx).getOrElse(line).trim
    )

    if violations.isEmpty then Right(code)
    else Left(violations)

  /** Format validation violations into a human-readable error report. */
  def formatErrors(violations: List[ValidationViolation]): String =
    val header = s"Code validation failed (${violations.size} violation${if violations.size > 1 then "s" else ""}):"
    val details = violations.map: v =>
      s"  [${v.ruleId}] Line ${v.lineNumber}: ${v.description}\n    > ${v.snippet}"
    (header :: details).mkString("\n")
