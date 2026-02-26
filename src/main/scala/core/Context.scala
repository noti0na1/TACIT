package tacit.core

import tacit.executor.CodeRecorder

case class Context(
  config: Config,
  recorder: Option[CodeRecorder],
)

object Context:

  def usingContext[R](config: Config)(op: Context ?=> R): R  =
    val recorder: Option[CodeRecorder] = config.recordPath.map: dir =>
      new CodeRecorder(java.io.File(dir))
    val myCtx = Context(config, recorder)
    try op(using myCtx)
    finally
      recorder.foreach(_.close())

  def ctx(using c: Context): Context = c

end Context
