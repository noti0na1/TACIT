package library

import java.io.{BufferedReader, StringReader}
import java.nio.file.{Path, Paths}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import language.experimental.captureChecking

class VirtualFileSystem(
  val root: Path,
  check: String -> Boolean = _ => true,
  initialFiles: Map[String, String] = Map.empty,
  classifiedPaths: Set[Path] = Set.empty
) extends FileSystem:
  private val normalizedRoot = root.toAbsolutePath.normalize
  private val normalizedClassified = classifiedPaths.map(_.toAbsolutePath.normalize)
  private val files: mutable.Map[Path, Array[Byte]] = mutable.Map.empty
  private val directories: mutable.Set[Path] = mutable.Set(normalizedRoot)

  initialFiles.foreach { (relPath, content) =>
    val resolved = normalizedRoot.resolve(relPath).normalize
    ensureParentDirs(resolved)
    files(resolved) = content.getBytes
  }

  private def ensureParentDirs(path: Path): Unit =
    var parent: Path | Null = path.getParent
    while parent != null do
      directories += parent
      parent = parent.getParent

  private def resolvePath(target: String): Path =
    val resolved = Paths.get(target).toAbsolutePath.normalize
    if !resolved.startsWith(normalizedRoot) then
      throw SecurityException(
        s"Access denied: $resolved is outside virtual root $normalizedRoot"
      )
    resolved

  private def checkPath(resolved: Path): Unit =
    val relativePath = normalizedRoot.relativize(resolved).toString
    if relativePath.nonEmpty && !check(relativePath) then
      throw SecurityException(
        s"Access denied: path '$relativePath' did not pass the check"
      )

  private def isClassifiedPath(resolved: Path): Boolean =
    normalizedClassified.exists(cp => resolved.startsWith(cp) || resolved == cp)

  private def requireNotClassified(resolved: Path, op: String): Unit =
    if isClassifiedPath(resolved) then
      throw SecurityException(
        s"Access denied: '$op' is not allowed on classified path $resolved. Use classified operations instead."
      )

  private def requireClassified(resolved: Path, op: String): Unit =
    if !isClassifiedPath(resolved) then
      throw SecurityException(
        s"Access denied: '$op' is only allowed on classified paths, but $resolved is not classified."
      )

  private class FileEntryImpl(resolved: Path) extends FileEntry(this):
    def path: String = resolved.toString
    def name: String = resolved.getFileName.nn.toString
    def read(): String =
      requireNotClassified(resolved, "read")
      String(readBytes())
    def readBytes(): Array[Byte] =
      requireNotClassified(resolved, "readBytes")
      files.getOrElse(resolved, throw java.nio.file.NoSuchFileException(resolved.toString))

    def write(content: String): Unit =
      requireNotClassified(resolved, "write")
      ensureParentDirs(resolved)
      files(resolved) = content.getBytes

    def append(content: String): Unit =
      requireNotClassified(resolved, "append")
      val existing = files.getOrElse(resolved, Array.empty[Byte])
      ensureParentDirs(resolved)
      files(resolved) = existing ++ content.getBytes

    def readLines(): List[String] =
      requireNotClassified(resolved, "readLines")
      val raw = files.getOrElse(resolved, throw java.nio.file.NoSuchFileException(resolved.toString))
      val content = String(raw)
      val reader = BufferedReader(StringReader(content))
      val lines = ListBuffer[String]()
      var line: String | Null = reader.readLine()
      while line != null do
        lines += line
        line = reader.readLine()
      reader.close()
      lines.toList

    def delete(): Unit =
      requireNotClassified(resolved, "delete")
      if !files.contains(resolved) then
        throw java.nio.file.NoSuchFileException(resolved.toString)
      files.remove(resolved)
      ()

    def exists: Boolean =
      files.contains(resolved) || directories.contains(resolved)

    def isDirectory: Boolean = directories.contains(resolved)

    def size: Long =
      files.get(resolved).map(_.length.toLong).getOrElse(0L)

    def children: List[FileEntry^{origin}] =
      requireNotClassified(resolved, "children")
      if !directories.contains(resolved) then
        throw java.nio.file.NoSuchFileException(resolved.toString)
      val childFiles = files.keys.filter { p =>
        val parent = p.getParent
        parent != null && parent.nn == resolved
      }
      val childDirs = directories.filter { d =>
        val parent = d.getParent
        d != resolved && parent != null && parent.nn == resolved
      }
      (childFiles ++ childDirs).toList.map(p => new FileEntryImpl(p)).sortBy(_.path)

    def walk(): List[FileEntry^{origin}] =
      requireNotClassified(resolved, "walk")
      val allPaths = directories.filter(d => d.startsWith(resolved) && d != resolved) ++
        files.keys.filter(_.startsWith(resolved))
      allPaths.toList.map(p => new FileEntryImpl(p))

    def isClassified: Boolean = isClassifiedPath(resolved)

    def readClassified(): Classified[String] =
      requireClassified(resolved, "readClassified")
      val bytes = files.getOrElse(resolved, throw java.nio.file.NoSuchFileException(resolved.toString))
      ClassifiedImpl.wrap(String(bytes))

    def writeClassified(content: Classified[String]): Unit =
      requireClassified(resolved, "writeClassified")
      ensureParentDirs(resolved)
      files(resolved) = ClassifiedImpl.unwrap(content).getBytes
  end FileEntryImpl

  def access(path: String): FileEntry^{this} =
    val resolved = resolvePath(path)
    checkPath(resolved)
    new FileEntryImpl(resolved)

  def forceAccess(path: String): FileEntry^{this} =
    val resolved = resolvePath(path)
    new FileEntryImpl(resolved)
