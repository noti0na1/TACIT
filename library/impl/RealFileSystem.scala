package library

import java.nio.file.{Files, FileVisitResult, Path, Paths, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*
import scala.collection.mutable.ListBuffer
import language.experimental.captureChecking

class RealFileSystem(val root: Path, check: String -> Boolean = _ => true) extends FileSystem:
  private val normalizedRoot = root.toAbsolutePath.normalize

  private def resolvePath(target: String): Path =
    val resolved = Paths.get(target).toAbsolutePath.normalize
    if !resolved.startsWith(normalizedRoot) then
      throw SecurityException(
        s"Access denied: $resolved is outside root $normalizedRoot"
      )
    resolved

  private def checkPath(resolved: Path): Unit =
    val relativePath = normalizedRoot.relativize(resolved).toString
    if relativePath.nonEmpty && !check(relativePath) then
      throw SecurityException(
        s"Access denied: path '$relativePath' did not pass the check"
      )

  private class FileEntryImpl(jpath: Path) extends FileEntry(RealFileSystem.this):
    def path: String = jpath.toString
    def name: String = jpath.getFileName.nn.toString
    def read(): String = String(Files.readAllBytes(jpath))
    def readBytes(): Array[Byte] = Files.readAllBytes(jpath)
    def write(content: String): Unit =
      Files.createDirectories(jpath.getParent)
      Files.write(jpath, content.getBytes)
      ()

    def append(content: String): Unit =
      Files.createDirectories(jpath.getParent)
      Files.write(jpath, content.getBytes,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND)
      ()

    def readLines(): List[String] =
      Files.readAllLines(jpath).nn.asScala.toList

    def delete(): Unit = Files.delete(jpath)
    def exists: Boolean = Files.exists(jpath)
    def isDirectory: Boolean = Files.isDirectory(jpath)
    def size: Long = Files.size(jpath)

    def children: List[FileEntry^{RealFileSystem.this}] =
      Files.list(jpath).nn.iterator.nn.asScala
        .map(p => new FileEntryImpl(p))
        .toList

    def walk(): List[FileEntry^{RealFileSystem.this}] =
      val paths = ListBuffer[Path]()
      Files.walkFileTree(jpath, new SimpleFileVisitor[Path]:
        override def visitFile(file: Path | Null, attrs: BasicFileAttributes | Null): FileVisitResult =
          paths += file.nn
          FileVisitResult.CONTINUE
        override def preVisitDirectory(dir: Path | Null, attrs: BasicFileAttributes | Null): FileVisitResult =
          val d = dir.nn
          if d != jpath then
            paths += d
          FileVisitResult.CONTINUE
      )
      paths.toList.map(p => new FileEntryImpl(p))
  end FileEntryImpl

  def access(path: String): FileEntry^{this} =
    val resolved = resolvePath(path)
    checkPath(resolved)
    new FileEntryImpl(resolved)
