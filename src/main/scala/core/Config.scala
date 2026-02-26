package tacit.core

import scopt.OParser
import tacit.library.LlmConfig

case class Config(
  recordPath: Option[String] = None,
  strictMode: Boolean = false,
  classifiedPaths: Set[String] = Set.empty,
  llmConfig: Option[LlmConfig] = None,
  quiet: Boolean = false,
  wrappedCode: Boolean = true,
  sessionEnabled: Boolean = true,
)

object Config:
  private def warn(msg: String): Unit =
    System.err.println(s"[SafeExecMCP][config] WARNING: $msg")

  private def mergeFromFile(base: Config, path: String): Config =
    import io.circe.parser.{parse => parseJson}
    val file = java.io.File(path)
    if !file.exists() then
      throw RuntimeException(s"Config file not found: '$path'")
    if !file.canRead then
      throw RuntimeException(s"Config file is not readable: '$path'")

    val source = scala.io.Source.fromFile(file)
    val content = try source.mkString finally source.close()
    val json = parseJson(content) match
      case Left(err) => throw RuntimeException(s"Failed to parse config file '$path': ${err.message}")
      case Right(j) => j
    val cursor = json.hcursor
    val recordPath = cursor.get[String]("recordPath").toOption.orElse(base.recordPath)
    val strictMode = cursor.get[Boolean]("strictMode").toOption.getOrElse(base.strictMode)
    val quiet = cursor.get[Boolean]("quiet").toOption.getOrElse(base.quiet)
    val wrappedCode = cursor.get[Boolean]("wrappedCode").toOption.getOrElse(base.wrappedCode)
    val sessionEnabled = cursor.get[Boolean]("sessionEnabled").toOption.getOrElse(base.sessionEnabled)
    val classifiedPaths = cursor.downField("classifiedPaths").as[List[String]].toOption
      .map(_.toSet).getOrElse(base.classifiedPaths)
    val llmConfig = cursor.downField("llm").focus.flatMap { llmJson =>
      val c = llmJson.hcursor
      val baseUrl = c.get[String]("baseUrl").toOption
      val apiKey  = c.get[String]("apiKey").toOption
      val model   = c.get[String]("model").toOption
      (baseUrl, apiKey, model) match
        case (Some(b), Some(a), Some(m)) => Some(LlmConfig(b, a, m))
        case (None, None, None) => None
        case _ =>
          val missing = Seq("baseUrl" -> baseUrl, "apiKey" -> apiKey, "model" -> model)
            .collect { case (name, None) => name }
          warn(s"Incomplete LLM config in '$path': missing ${missing.mkString(", ")}. LLM config ignored.")
          None
    }.orElse(base.llmConfig)
    base.copy(
      recordPath = recordPath,
      strictMode = strictMode,
      classifiedPaths = classifiedPaths,
      llmConfig = llmConfig,
      quiet = quiet,
      wrappedCode = wrappedCode,
      sessionEnabled = sessionEnabled,
    )

  /** Validate that LlmConfig doesn't have empty-string fields (from partial CLI flags). */
  private def validateLlmConfig(config: Config): Config =
    config.llmConfig match
      case Some(llm) =>
        val missing = Seq("baseUrl" -> llm.baseUrl, "apiKey" -> llm.apiKey, "model" -> llm.model)
          .collect { case (name, v) if v.isEmpty => name }
        if missing.nonEmpty then
          warn(s"Incomplete LLM config: missing ${missing.mkString(", ")}. LLM config ignored.")
          config.copy(llmConfig = None)
        else config
      case None => config

  val optParser =
    val builder = OParser.builder[Config]
    import builder.*
    OParser.sequence(
      programName("SafeExecMCP"),
      opt[String]('r', "record")
        .action((x, c) => c.copy(recordPath = Some(x)))
        .text("Record code execution requests in the given directory."),
      opt[Unit]('s', "strict")
        .action((_, c) => c.copy(strictMode = true))
        .text("Enable strict mode: block file operations (cat, ls, rm, etc.) through exec."),
      opt[String]("classified-paths")
        .action((x, c) => c.copy(classifiedPaths = x.split(",").map(_.trim).filter(_.nonEmpty).toSet))
        .text("Comma-separated list of classified (protected) paths."),
      opt[Unit]('q', "quiet")
        .action((_, c) => c.copy(quiet = true))
        .text("Suppress startup banner and request/response logging."),
      opt[Unit]("no-wrap")
        .action((_, c) => c.copy(wrappedCode = false))
        .text("Disable wrapping user code in def run() = ... ; run() (workaround for capture checking REPL errors)."),
      opt[Unit]("no-session")
        .action((_, c) => c.copy(sessionEnabled = false))
        .text("Disable session-related tools (create/execute/delete/list sessions)."),
      opt[String]('c', "config")
        .action((x, c) => mergeFromFile(c, x))
        .text("Path to JSON config file."),
      opt[String]("llm-base-url")
        .action((x, c) =>
          val llm = c.llmConfig.getOrElse(LlmConfig("", "", ""))
          c.copy(llmConfig = Some(llm.copy(baseUrl = x)))
        )
        .text("LLM API base URL."),
      opt[String]("llm-api-key")
        .action((x, c) =>
          val llm = c.llmConfig.getOrElse(LlmConfig("", "", ""))
          c.copy(llmConfig = Some(llm.copy(apiKey = x)))
        )
        .text("LLM API key."),
      opt[String]("llm-model")
        .action((x, c) =>
          val llm = c.llmConfig.getOrElse(LlmConfig("", "", ""))
          c.copy(llmConfig = Some(llm.copy(model = x)))
        )
        .text("LLM model name."),
    )

  def parseCliArgs(args: Array[String]): Option[Config] =
    OParser.parse(optParser, args, Config()).map(validateLlmConfig)
