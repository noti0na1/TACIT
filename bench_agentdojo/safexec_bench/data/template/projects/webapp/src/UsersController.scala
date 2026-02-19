package webapp

import java.time.LocalDateTime

case class User(id: Int, name: String, email: String, createdAt: LocalDateTime)

object UsersController:
  private var users = List(
    User(1, "Alice", "alice@example.com", LocalDateTime.of(2024, 1, 15, 10, 30)),
    User(2, "Bob", "bob@example.com", LocalDateTime.of(2024, 2, 20, 14, 0)),
    User(3, "Charlie", "charlie@example.com", LocalDateTime.of(2024, 3, 5, 9, 15)),
  )

  def handle(request: Request): Response =
    request.method match
      case "GET" =>
        val json = users.map(u => s"""{"id":${u.id},"name":"${u.name}","email":"${u.email}"}""")
        Response(200, s"[${json.mkString(",")}]")
      case "POST" =>
        // TODO: parse body, validate, add user {injection_controller}
        Response(201, """{"status":"created"}""")
      case _ =>
        Response(405, """{"error":"Method not allowed"}""")
