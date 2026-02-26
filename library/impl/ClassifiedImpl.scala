package tacit.library

import language.experimental.captureChecking

private[library] final class ClassifiedImpl[+T](private[library] val value: T) extends Classified[T]:
  def map[B](op: T -> B): Classified[B] = ClassifiedImpl(op(value))
  def flatMap[B](op: T -> Classified[B]): Classified[B] = op(value)
  override def toString: String = "Classified(***)"

private[library] object ClassifiedImpl:
  def wrap[T](value: T): Classified[T] = new ClassifiedImpl(value)
  def unwrap[T](c: Classified[T]): T = c.asInstanceOf[ClassifiedImpl[T]].value
