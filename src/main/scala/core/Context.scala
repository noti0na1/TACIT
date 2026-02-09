package core

import executor.CodeRecorder
import config.Config

case class Context(
  recorder: Option[CodeRecorder],
  strictMode: Boolean,
)

object Context:

  def usingContext[R](config: Config)(op: Context ?=> R): R  =
    val recorder: Option[CodeRecorder] = config.recordPath.map: dir =>
      new CodeRecorder(java.io.File(dir))
    val myCtx = Context(recorder, config.strictMode)
    try op(using myCtx)
    finally
      recorder.foreach(_.close())

  def ctx(using c: Context): Context = c

end Context
