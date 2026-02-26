package tacit.library

import java.nio.file.Path
import language.experimental.captureChecking

class InterfaceImpl(
  private val createFS: (String, String -> Boolean, Set[Path]) => FileSystem^,
  private val strictMode: Boolean = false,
  private val classifiedPaths: Set[Path] = Set.empty,
  private val llmConfig: Option[LlmConfig] = None
) extends Interface:
  export FileOps.*
  export ProcessOps.*
  export WebOps.*

  private val llmOps = new LlmOps(llmConfig)
  export llmOps.chat

  def println(x: Any)(using IOCapability): Unit = scala.Predef.println(x)
  
  def println()(using IOCapability): Unit = scala.Predef.println()

  def print(x: Any)(using IOCapability): Unit = scala.Predef.print(x)

  def printf(fmt: String, args: Any*)(using IOCapability): Unit = scala.Predef.printf(fmt, args*)

  def requestFileSystem[T](root: String)(op: FileSystem^ ?=> T)(using IOCapability): T =
    val rootPath = Path.of(root).toAbsolutePath.normalize
    val relevantClassified = classifiedPaths
      .map(_.toAbsolutePath.normalize)
      .filter(cp => cp.startsWith(rootPath) || rootPath.startsWith(cp))
    val fs = createFS(root, _ => true, relevantClassified)
    op(using fs)

  def requestExecPermission[T](commands: Set[String])(op: ProcessPermission^ ?=> T)(using IOCapability): T =
    val perm = new ProcessPermission(commands, strictMode)
    op(using perm)

  def requestNetwork[T](hosts: Set[String])(op: Network^ ?=> T)(using IOCapability): T =
    val net = new Network(hosts)
    op(using net)

  def classify[T](value: T): Classified[T] = ClassifiedImpl.wrap(value)

  def access(path: String)(using fs: FileSystem): FileEntry^{fs} =
    fs.access(path)

  def readClassified(path: String)(using fs: FileSystem): Classified[String] =
    fs.access(path).readClassified()

  def writeClassified(path: String, content: Classified[String])(using fs: FileSystem): Unit =
    fs.access(path).writeClassified(content)