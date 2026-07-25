Implement a small REST API in Scala 3 using http4s, built with scala-cli.

## Requirements

### Build configuration
The project is compiled by `scala-cli`. Put the dependency directives at the top of the main file, using exactly these:

```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4
```

### Data model
```scala
case class Task(id: Long, title: String, done: Boolean)
```
`id` is assigned by the server, starting at 1 and incrementing.

### Endpoints
| Method | Path | Behaviour |
|---|---|---|
| GET | `/health` | 200, body `{"status":"ok"}` |
| GET | `/tasks` | 200, JSON array of all tasks, ordered by ascending id |
| POST | `/tasks` | Body `{"title":"..."}`. Creates a task with `done=false`. Returns 201 with the created task. |
| GET | `/tasks/{id}` | 200 with the task, or 404 if absent |
| PUT | `/tasks/{id}` | Body `{"title":"...","done":true}`. Returns 200 with the updated task, or 404 if absent. |
| DELETE | `/tasks/{id}` | 204 if deleted, 404 if absent |

### Implementation constraints
- Use `cats.effect.IO` and http4s' `HttpRoutes`.
- In-memory state only — use `cats.effect.Ref` for the store.
- Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`, plus a helper
  `def freshApp: IO[HttpApp[IO]]` that allocates new state and returns the routes as an `HttpApp`,
  so tests can obtain an isolated instance.
- Derive circe codecs for `Task` (e.g. `io.circe.generic.auto._` or explicit `Encoder`/`Decoder`).
- Provide an `object Main extends IOApp.Simple` that serves on port 3000 via Ember.

### Tests
Put tests in `main.test.scala` using **munit**. Drive the `HttpApp[IO]` directly by constructing
`Request[IO]` values and calling `.run(...)` — do **not** bind a real TCP port. Cover at minimum:
1. `GET /health` returns 200
2. `POST /tasks` returns 201 and id 1
3. `GET /tasks/1` after creation returns the task
4. `GET /tasks/999` returns 404
5. `DELETE` an existing task returns 204, and a subsequent `GET` returns 404

## Output format

Output **only** the files, each introduced by a `### FILE: <relative path>` line followed by a single fenced code block. No commentary before, between, or after the files.

### FILE: main.scala
```scala
...
```

### FILE: main.test.scala
```scala
...
```
