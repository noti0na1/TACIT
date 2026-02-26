package tacit.executor

import scala.collection.mutable
import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.UUID
import dotty.tools.repl.{ReplDriver, State}
import java.nio.charset.StandardCharsets
import tacit.core.{Config, Context}
import Context.*

/** Result of code execution */
case class ExecutionResult(
    success: Boolean,
    output: String,
    error: Option[String] = None
)

/** Executes Scala code snippets */
object ScalaExecutor:

  /** Computes the classpath for the embedded Scala REPL.
    *
    * `-usejavacp` only reads `java.class.path`, which can be incomplete
    * (e.g. fat JAR, custom classloader). This builds an explicit
    * classpath by also locating Scala core libraries via loaded classes.
    */
  private[executor] lazy val replClasspathArgs: Array[String] =
    val paths = mutable.LinkedHashSet[String]()

    // java.class.path — covers sbt run, java -cp, etc.
    Option(System.getProperty("java.class.path")).foreach { cp =>
      paths ++= cp.split(java.io.File.pathSeparator).filter(_.nonEmpty)
    }

    // Locate the Scala standard library via a loaded class.
    // This covers cases where java.class.path doesn't list it
    // (fat JARs, app servers, custom launchers).
    // Only the stdlib is included — compiler/REPL internals are
    // intentionally kept off the executed code's classpath.
    val markerClasses: List[Class[?]] = List(
      classOf[scala.Unit],                    // scala-library
    )
    for cls <- markerClasses do
      try
        val loc = cls.getProtectionDomain.getCodeSource.getLocation
        paths += java.nio.file.Paths.get(loc.toURI).toString
      catch case _: Exception => ()

    Array(
      "-classpath", paths.mkString(java.io.File.pathSeparator),
      "-color:never",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Yexplicit-nulls",
      // "-Yno-predef",
      "-Wsafe-init",
      "-language:experimental.captureChecking",
      // "-language:experimental.separationChecking",
      "-language:experimental.modularity"
    )

  /** Preamble code injected before user code to make the library API available. */
  private[executor] def libraryPreamble(cfg: Config): String =
    val classifiedExpr =
      if cfg.classifiedPaths.isEmpty then "Set.empty[java.nio.file.Path]"
      else cfg.classifiedPaths
        .map(p => s"""java.nio.file.Path.of("$p").toAbsolutePath.normalize""")
        .mkString("Set(", ", ", ")")
    def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    val llmConfigExpr = cfg.llmConfig match
      case None => "None"
      case Some(llm) => s"""Some(LlmConfig("${esc(llm.baseUrl)}", "${esc(llm.apiKey)}", "${esc(llm.model)}"))"""
    s"""|import tacit.library.*
        |val api: Interface = new InterfaceImpl(
        |  (root, check, classified) => new RealFileSystem(java.nio.file.Path.of(root), check, classified),
        |  ${cfg.strictMode},
        |  $classifiedExpr,
        |  $llmConfigExpr
        |)
        |// import Predef.{print => _, println => _, printf => _, readLine => _, readInt => _, readDouble => _}
        |import api.*
        |given IOCapability = null.asInstanceOf[IOCapability]
        |""".stripMargin

  /** Wraps user code in a `def run() = ...; run()` block to avoid capture checking REPL errors. */
  private[executor]  def wrapCode(code: String, wrap: Boolean): String =
    if !wrap then code
    else
      // val whitespace = code.takeWhile(_.isWhitespace)
      val indented = code.linesIterator.map(line => s"  $line").mkString("\n")
      s"def run()(using IOCapability): Any =\n$indented\nrun()"

  /** Returns Some(errorResult) on validation failure, None on success. */
  private[executor] def validated(code: String): Option[ExecutionResult] =
    CodeValidator.validate(code).left.toOption.map(violations =>
      ExecutionResult(false, "", Some(CodeValidator.formatErrors(violations)))
    )

  /** Redirects stdout/stderr to the given print stream, runs the block, and captures output.
    * Detects Scala 3 compilation errors in the output (lines starting with `-- [E`)
    * and sets success=false accordingly.
    */
  private[executor] def withOutputCapture(
    outputCapture: ByteArrayOutputStream,
    printStream: PrintStream
  )(run: => Unit): ExecutionResult =
    outputCapture.reset()
    try
      val oldOut = System.out
      val oldErr = System.err
      System.setOut(printStream)
      System.setErr(printStream)
      try run
      finally
        System.setOut(oldOut)
        System.setErr(oldErr)
      printStream.flush()
      val output = outputCapture.toString(StandardCharsets.UTF_8).trim
      val hasCompileErrors = output.linesIterator.exists(_.startsWith("-- [E"))
      ExecutionResult(!hasCompileErrors, output)
    catch
      case e: Exception =>
        printStream.flush()
        ExecutionResult(false, outputCapture.toString(StandardCharsets.UTF_8).trim,
          Some(s"${e.getClass.getSimpleName}: ${e.getMessage}"))

  /** Execute a Scala code snippet stateless and return the result */
  def execute(code: String)(using Context): ExecutionResult =
    validated(code).getOrElse:
      val outputCapture = new ByteArrayOutputStream()
      val printStream = new PrintStream(outputCapture, true, StandardCharsets.UTF_8)
      val driver = new ReplDriver(replClasspathArgs, printStream, Some(getClass.getClassLoader))
      var state = driver.run(libraryPreamble(ctx.config))(using driver.initialState)
      withOutputCapture(outputCapture, printStream):
        state = driver.run(wrapCode(code, ctx.config.wrappedCode))(using state)
end ScalaExecutor

/** A REPL session that maintains state across executions */
class ReplSession(val id: String)(using Context):
  import ScalaExecutor.*

  private val outputCapture = new ByteArrayOutputStream()
  private val printStream = new PrintStream(outputCapture, true, StandardCharsets.UTF_8)

  private val driver = new ReplDriver(
    replClasspathArgs,
    printStream,
    Some(getClass.getClassLoader)
  )
  private var state: State =
    val s0 = driver.initialState
    // Run preamble once to make library API available in the session
    driver.run(libraryPreamble(ctx.config))(using s0)

  /** Execute code in this session and return the result */
  def execute(code: String): ExecutionResult =
    validated(code).getOrElse:
      withOutputCapture(outputCapture, printStream):
        state = driver.run(code)(using state)

object ReplSession:
  def create(using Context): ReplSession = new ReplSession(UUID.randomUUID().toString)

/** Manages multiple REPL sessions */
class SessionManager(using Context):
  private val sessions = mutable.Map[String, ReplSession]()

  /** Create a new session and return its ID */
  def createSession(): String =
    val session = ReplSession.create
    sessions(session.id) = session
    session.id

  /** Delete a session by ID */
  def deleteSession(sessionId: String): Boolean =
    sessions.remove(sessionId).isDefined

  /** Get a session by ID */
  def getSession(sessionId: String): Option[ReplSession] =
    sessions.get(sessionId)

  /** Execute code in a specific session */
  def executeInSession(sessionId: String, code: String): Either[String, ExecutionResult] =
    sessions.get(sessionId) match
      case Some(session) => Right(session.execute(code))
      case None => Left(s"Session not found: $sessionId")

  /** List all active session IDs */
  def listSessions(): List[String] =
    sessions.keys.toList
