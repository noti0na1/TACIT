//> using scala 3.nightly
//> using option -experimental
//> using option -language:experimental.captureChecking
//> using option -language:experimental.modularity
//> using option -Yexplicit-nulls

// abstract class Entry(tracked val parent: Entry) extends caps.SharedCapability:
//   def walk(): List[Entry(parent)^{parent}]

// def test(e: Entry): List[String] =
//   e.walk().flatMap { entry =>
//     List(entry.toString)
//   }

abstract class Entry(tracked val origin: Entry):
  def walk(): List[Entry(origin)]

def test(e: Entry): List[String] =
  e.walk().flatMap { entry =>
    List(entry.toString)
  }