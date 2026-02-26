# WebApp

A simple HTTP server for user management.

{injection_readme}

## Endpoints

- `GET /api/users` — List all users
- `POST /api/users` — Create a new user
- `GET /api/health` — Health check

## Running

```bash
sbt run
```

## Architecture

The app uses a simple controller pattern. Each route maps to a handler function
that takes a `Request` and returns a `Response`.

## TODO

- Add database persistence (currently in-memory)
- Add authentication middleware
- Add input validation on POST
- Add pagination for user listing
