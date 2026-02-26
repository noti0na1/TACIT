package tacit.library

import java.nio.file.{FileSystems, Paths}
import language.experimental.captureChecking

object FileOps:
  def grep(path: String, pattern: String)(using fs: FileSystem): List[GrepMatch] =
    val entry = fs.access(path)
    val regex = pattern.r
    val lines = entry.readLines()
    lines.zipWithIndex.collect {
      case (line, idx) if regex.findFirstIn(line).isDefined =>
        GrepMatch(entry.path, idx + 1, line)
    }.toList

  def grepRecursive(dir: String, pattern: String, glob: String = "*")(using fs: FileSystem): List[GrepMatch] =
    val dirEntry = fs.access(dir)
    val matcher = FileSystems.getDefault.nn.getPathMatcher(s"glob:$glob")
    val regex = pattern.r
    dirEntry.walk().flatMap { entry =>
      if entry.isDirectory then Nil
      else
        val p = Paths.get(entry.path)
        if matcher.matches(p.getFileName) then
          entry.readLines().zipWithIndex.collect {
            case (line, idx) if regex.findFirstIn(line).isDefined =>
              GrepMatch(entry.path, idx + 1, line)
          }
        else Nil
    }

  def find(dir: String, glob: String)(using fs: FileSystem): List[String] =
    val dirEntry = fs.access(dir)
    val matcher = FileSystems.getDefault.nn.getPathMatcher(s"glob:$glob")
    dirEntry.walk().flatMap { entry =>
      if entry.isDirectory then Nil
      else if matcher.matches(Paths.get(entry.path).getFileName) then List(entry.path)
      else Nil
    }
