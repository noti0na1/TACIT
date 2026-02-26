package tacit.executor

import java.io.{File, PrintWriter, FileWriter}
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

/** Records user-submitted code and execution results to log files.
  *
  * Each call to `record` writes two files under `dir`:
  *   - `<timestamp>_<seq>_<session>.scala` — the submitted code
  *   - `<timestamp>_<seq>_<session>.result` — the execution result
  *
  * The sequence counter ensures unique filenames even when
  * multiple executions share the same millisecond timestamp.
  */
class CodeRecorder(dir: File):
  dir.mkdirs()

  private val tsFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS").withZone(ZoneOffset.UTC)
  private val counter = java.util.concurrent.atomic.AtomicLong(0)

  def record(code: String, sessionId: String, result: ExecutionResult): Unit =
    val ts = tsFormat.format(Instant.now())
    val seq = counter.getAndIncrement()
    val base = s"${ts}_%04d_$sessionId".format(seq)

    val codeFile = new PrintWriter(new FileWriter(File(dir, s"$base.scala")))
    try
      codeFile.print(code)
    finally
      codeFile.close()

    val resultFile = new PrintWriter(new FileWriter(File(dir, s"$base.result")))
    try
      val status = if result.success then "success" else "failure"
      resultFile.println(s"status: $status")
      resultFile.println(result.output)
      result.error.foreach { err =>
        resultFile.println(s"Error: $err")
      }
    finally
      resultFile.close()

  def close(): Unit = ()
