package config

import scopt.OParser

case class Config(
  recordPath: Option[String] = None,
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
    )

  def parseCliArgs(args: Array[String]): Option[Config] =
    OParser.parse(optParser, args, Config())
