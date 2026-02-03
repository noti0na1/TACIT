package library

import language.experimental.captureChecking

class InterfaceImpl(private val createFS: (String, String -> Boolean) => FileSystem^) extends Interface:
  export FileOps.*
  export ProcessOps.*
  export WebOps.*

  def requestFileSystem[T](root: String)(op: FileSystem^ ?=> T): T =
    val fs = createFS(root, _ => true)
    op(using fs)

  def requestExecPermission[T](commands: Set[String])(op: ProcessPermission^ ?=> T): T =
    val perm = new ProcessPermission(commands)
    op(using perm)

  def requestNetwork[T](hosts: Set[String])(op: Network^ ?=> T): T =
    val net = new Network(hosts)
    op(using net)

  def access(path: String)(using fs: FileSystem): FileEntry^{fs} =
    fs.access(path)