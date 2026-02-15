package executor

import scala.collection.mutable
import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.UUID
import dotty.tools.repl.{ReplDriver, State}
import java.nio.charset.StandardCharsets
import core.Context
import Context.*

/** Result of code execution */
case class ExecutionResult(
    success: Boolean,
    output: String,
    error: Option[String] = None
)

/** Computes the classpath for the embedded Scala REPL.
  *
  * `-usejavacp` only reads `java.class.path`, which can be incomplete
  * (e.g. fat JAR, custom classloader). This object builds an explicit
  * classpath by also locating Scala core libraries via loaded classes.
  */
private object ReplClasspath:
  lazy val args: Array[String] =
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
      "-Wsafe-init",
      "-language:experimental.captureChecking",
      "-language:experimental.modularity"
    )

/** Preamble code injected before user code to make the library API available. */
private def libraryPreamble(
  strictMode: Boolean,
  classifiedPaths: Set[String],
  llmConfig: Option[library.LlmConfig]
): String =
  val classifiedExpr =
    if classifiedPaths.isEmpty then "Set.empty[java.nio.file.Path]"
    else classifiedPaths
      .map(p => s"""java.nio.file.Path.of("$p").toAbsolutePath.normalize""")
      .mkString("Set(", ", ", ")")
  def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
  val llmConfigExpr = llmConfig match
    case None => "None"
    case Some(cfg) => s"""Some(LlmConfig("${esc(cfg.baseUrl)}", "${esc(cfg.apiKey)}", "${esc(cfg.model)}"))"""
  s"""|import library.*
      |val api: Interface = new InterfaceImpl(
      |  (root, check, classified) => new RealFileSystem(java.nio.file.Path.of(root), check, classified),
      |  $strictMode,
      |  $classifiedExpr,
      |  $llmConfigExpr
      |)
      |import api.*
      |""".stripMargin

/** A REPL session that maintains state across executions */
class ReplSession(val id: String)(using Context):
  private val outputCapture = new ByteArrayOutputStream()
  private val printStream = new PrintStream(outputCapture, true, StandardCharsets.UTF_8)

  private val driver = new ReplDriver(
    ReplClasspath.args,
    printStream,
    Some(getClass.getClassLoader)
  )
  private var state: State =
    val s0 = driver.initialState
    // Run preamble once to make library API available in the session
    driver.run(libraryPreamble(ctx.strictMode, ctx.classifiedPaths, ctx.llmConfig))(using s0)

  /** Execute code in this session and return the result */
  def execute(code: String): ExecutionResult =
    CodeValidator.validate(code) match
      case Left(violations) =>
        return ExecutionResult(
          success = false,
          output = "",
          error = Some(CodeValidator.formatErrors(violations))
        )
      case Right(_) => ()

    outputCapture.reset()

    try
      val oldOut = System.out
      val oldErr = System.err
      System.setOut(printStream)
      System.setErr(printStream)
      try
        state = driver.run(code)(using state)
      finally
        System.setOut(oldOut)
        System.setErr(oldErr)

      printStream.flush()
      val output = outputCapture.toString(StandardCharsets.UTF_8).trim
      ExecutionResult(success = true, output = output)
    catch
      case e: Exception =>
        printStream.flush()
        val output = outputCapture.toString(StandardCharsets.UTF_8).trim
        val errorMsg = s"${e.getClass.getSimpleName}: ${e.getMessage}"
        ExecutionResult(
          success = false,
          output = output,
          error = Some(errorMsg)
        )

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

/** Executes Scala code snippets in isolation (stateless) */
object ScalaExecutor:
  /** Execute a Scala code snippet and return the result */
  def execute(code: String)(using Context): ExecutionResult =
    CodeValidator.validate(code) match
      case Left(violations) =>
        return ExecutionResult(
          success = false,
          output = "",
          error = Some(CodeValidator.formatErrors(violations))
        )
      case Right(_) => ()

    val outputCapture = new ByteArrayOutputStream()
    val printStream = new PrintStream(outputCapture, true, StandardCharsets.UTF_8)

    try
      val driver = new ReplDriver(
        ReplClasspath.args,
        printStream,
        Some(getClass.getClassLoader)
      )
      var state = driver.initialState
      // Run preamble to make library API available
      state = driver.run(libraryPreamble(ctx.strictMode, ctx.classifiedPaths, ctx.llmConfig))(using state)
      outputCapture.reset()

      val oldOut = System.out
      val oldErr = System.err
      System.setOut(printStream)
      System.setErr(printStream)
      try
        state = driver.run(code)(using state)
      finally
        System.setOut(oldOut)
        System.setErr(oldErr)

      printStream.flush()
      val output = outputCapture.toString(StandardCharsets.UTF_8).trim
      ExecutionResult(success = true, output = output)
    catch
      case e: Exception =>
        printStream.flush()
        val output = outputCapture.toString(StandardCharsets.UTF_8).trim
        val errorMsg = s"${e.getClass.getSimpleName}: ${e.getMessage}"
        ExecutionResult(
          success = false,
          output = output,
          error = Some(errorMsg)
        )
