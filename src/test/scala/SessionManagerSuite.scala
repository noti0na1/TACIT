import tacit.executor.SessionManager
import tacit.core.{Context, Config}

class SessionManagerSuite extends munit.FunSuite:
  given Context = Context(Config(), None)

  test("create and list sessions"):
    val manager = new SessionManager
    val sessionId = manager.createSession()
    assert(sessionId.nonEmpty)
    assert(manager.listSessions().contains(sessionId))

  test("delete session"):
    val manager = new SessionManager
    val sessionId = manager.createSession()
    assert(manager.deleteSession(sessionId))
    assert(!manager.listSessions().contains(sessionId))

  test("delete non-existent session"):
    val manager = new SessionManager
    assert(!manager.deleteSession("non-existent-id"))

  test("execute in session maintains state"):
    val manager = new SessionManager
    val sessionId = manager.createSession()

    // Define a variable
    val result1 = manager.executeInSession(sessionId, "val x = 42")
    assert(result1.isRight)

    // Use the variable in next execution
    val result2 = manager.executeInSession(sessionId, "x * 2")
    assert(result2.isRight)
    assert(result2.toOption.get.output.contains("84"))

  test("execute in non-existent session"):
    val manager = new SessionManager
    val result = manager.executeInSession("non-existent-id", "1 + 1")
    assert(result.isLeft)
