package config

import scopt.OParser

case class Config(
  recordPath: Option[String] = None,
  strictMode: Boolean = false,
)

object Config:
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
    )

  def parseCliArgs(args: Array[String]): Option[Config] =
    OParser.parse(optParser, args, Config())
