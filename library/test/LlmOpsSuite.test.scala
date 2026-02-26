package tacit.library

class LlmOpsSuite extends munit.FunSuite:

  test("chat(String) with no config throws RuntimeException") {
    val ops = new LlmOps(None)
    val ex = intercept[RuntimeException] { ops.chat("hello") }
    assert(ex.getMessage.nn.contains("not configured"))
  }

  test("chat(Classified[String]) with no config throws RuntimeException") {
    val ops = new LlmOps(None)
    val ex = intercept[RuntimeException] { ops.chat(ClassifiedImpl.wrap("hello")) }
    assert(ex.getMessage.nn.contains("not configured"))
  }
