package library

import java.io.{BufferedReader, InputStreamReader}
import java.util.concurrent.TimeUnit
import language.experimental.captureChecking

object ProcessOps:
  def exec(
    command: String,
    args: List[String] = List.empty,
    workingDir: Option[String] = None,
    timeoutMs: Long = 30000
  )(using pp: ProcessPermission): ProcessResult =
    pp.validateCommand(command)
    val cmdList = new java.util.ArrayList[String]()
    cmdList.add(command)
    args.foreach(cmdList.add)
    val pb = new ProcessBuilder(cmdList)
    workingDir.foreach(d => pb.directory(java.io.File(d)))
    val process = pb.start().nn
    var stdout = ""
    var stderr = ""
    val t1 = Thread: () =>
      val reader = BufferedReader(InputStreamReader(process.getInputStream))
      val sb = StringBuilder()
      var line = reader.readLine()
      while line != null do
        if sb.nonEmpty then sb.append('\n')
        sb.append(line)
        line = reader.readLine()
      reader.close()
      stdout = sb.toString
    val t2 = Thread: () =>
      val reader = BufferedReader(InputStreamReader(process.getErrorStream))
      val sb = StringBuilder()
      var line = reader.readLine()
      while line != null do
        if sb.nonEmpty then sb.append('\n')
        sb.append(line)
        line = reader.readLine()
      reader.close()
      stderr = sb.toString
    t1.start()
    t2.start()
    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if !finished then
      process.destroyForcibly()
      t1.join(1000)
      t2.join(1000)
      throw RuntimeException(s"Process '$command' timed out after ${timeoutMs}ms")
    t1.join()
    t2.join()
    ProcessResult(process.exitValue(), stdout, stderr)

  def execOutput(
    command: String,
    args: List[String] = List.empty
  )(using pp: ProcessPermission): String =
    exec(command, args).stdout
