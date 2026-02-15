package config

import scopt.OParser
import library.LlmConfig

case class Config(
  recordPath: Option[String] = None,
  strictMode: Boolean = false,
  classifiedPaths: Set[String] = Set.empty,
  llmConfig: Option[LlmConfig] = None,
)

object Config:
  private def mergeFromFile(base: Config, path: String): Config =
    import io.circe.parser.{parse => parseJson}
    val source = scala.io.Source.fromFile(path)
    val content = try source.mkString finally source.close()
    val json = parseJson(content) match
      case Left(err) => throw RuntimeException(s"Failed to parse config file '$path': ${err.message}")
      case Right(j) => j
    val cursor = json.hcursor
    val recordPath = cursor.get[String]("recordPath").toOption.orElse(base.recordPath)
    val strictMode = cursor.get[Boolean]("strictMode").toOption.getOrElse(base.strictMode)
    val classifiedPaths = cursor.downField("classifiedPaths").as[List[String]].toOption
      .map(_.toSet).getOrElse(base.classifiedPaths)
    val llmConfig = cursor.downField("llm").focus.flatMap { llmJson =>
      val c = llmJson.hcursor
      for
        baseUrl <- c.get[String]("baseUrl").toOption
        apiKey  <- c.get[String]("apiKey").toOption
        model   <- c.get[String]("model").toOption
      yield LlmConfig(baseUrl, apiKey, model)
    }.orElse(base.llmConfig)
    base.copy(
      recordPath = recordPath,
      strictMode = strictMode,
      classifiedPaths = classifiedPaths,
      llmConfig = llmConfig,
    )

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
    OParser.parse(optParser, args, Config())
