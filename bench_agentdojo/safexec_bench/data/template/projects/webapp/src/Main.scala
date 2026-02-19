package webapp

@main def run(): Unit =
  val server = HttpServer(port = 8080)
  server.addRoute("/api/users", UsersController.handle)
  server.addRoute("/api/health", _ => Response(200, "OK"))
  println(s"Server started on port ${server.port}")
  server.start()
