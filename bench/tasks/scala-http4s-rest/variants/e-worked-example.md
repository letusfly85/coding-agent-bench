Implement a small REST API in Scala 3 using http4s, built with scala-cli.

Below is a **complete, compiling** http4s service for a different resource. Follow its
structure exactly — the same imports, the same way of returning responses, the same way
of threading state through `Ref`. Then write the equivalent for tasks.

## Worked example (this compiles as-is)

### FILE: notes.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._

case class Note(id: Long, body: String)
case class CreateNoteReq(body: String)

object NoteApi:
  def routes(store: Ref[IO, Map[Long, Note]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {

      case GET -> Root / "ping" =>
        Ok(Map("status" -> "ok"))

      case GET -> Root / "notes" =>
        store.get.flatMap(m => Ok(m.values.toList.sortBy(_.id)))

      case req @ POST -> Root / "notes" =>
        for
          input <- req.as[CreateNoteReq]
          id    <- counter.updateAndGet(_ + 1)
          note   = Note(id, input.body)
          _     <- store.update(_.updated(id, note))
          resp  <- Created(note)
        yield resp

      case GET -> Root / "notes" / LongVar(id) =>
        store.get.flatMap { m =>
          m.get(id) match
            case Some(note) => Ok(note)
            case None       => NotFound()
        }

      case DELETE -> Root / "notes" / LongVar(id) =>
        store.modify(m => (m - id, m.contains(id))).flatMap { existed =>
          if existed then NoContent() else NotFound()
        }
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store   <- Ref.of[IO, Map[Long, Note]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

object NoteMain extends IOApp.Simple:
  val run: IO[Unit] =
    NoteApi.freshApp.flatMap { app =>
      EmberServerBuilder.default[IO]
        .withHost(host"0.0.0.0").withPort(port"3000")
        .withHttpApp(app).build.useForever
    }
```

### Points to carry over

- Every route body must end up as `IO[Response[IO]]`. `Ok(x)` **already returns**
  `IO[Response[IO]]`, so use `flatMap` (not `map`) when you produce it inside another `IO`.
- In a `for` comprehension over `IO`, bind with a plain identifier (`input <- req.as[T]`).
  Destructuring with a case-class pattern requires `withFilter`, which `IO` does not have.
- `Ref.modify` returns `(newState, result)` — use it when you need to know the state
  *before* the update. `Ref.updateAndGet` only gives you the state after.
- `EmberServerBuilder` needs `host"..."` / `port"..."` literals, not `String` / `Int`.

## Now implement the task API

Same structure, for this model:

```scala
case class Task(id: Long, title: String, done: Boolean)
```

| Method | Path | Behaviour |
|---|---|---|
| GET | `/health` | 200, body `{"status":"ok"}` |
| GET | `/tasks` | 200, JSON array of all tasks, ordered by ascending id |
| POST | `/tasks` | Body `{"title":"..."}`. Creates with `done=false`, id from 1. Returns 201 with the task. |
| GET | `/tasks/{id}` | 200 with the task, or 404 |
| PUT | `/tasks/{id}` | Body `{"title":"...","done":true}`. Returns 200 with the updated task, or 404. |
| DELETE | `/tasks/{id}` | 204 if deleted, 404 if absent |

Expose `def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO]`
and `def freshApp: IO[HttpApp[IO]]`, plus `object Main extends IOApp.Simple` serving on 3000.

## Tests

Put tests in `main.test.scala` using **munit**. Drive the `HttpApp[IO]` directly by
constructing `Request[IO]` values and calling `.run(...)` — do **not** bind a real TCP port.
Cover at minimum:

1. `GET /health` returns 200
2. `POST /tasks` returns 201 and id 1
3. `GET /tasks/1` after creation returns the task
4. `GET /tasks/999` returns 404
5. `DELETE` an existing task returns 204, and a subsequent `GET` returns 404

## Output format

Output **only** the files, each introduced by a `### FILE: <relative path>` line followed by
a single fenced code block. No commentary. Do **not** include the notes example in your output.

### FILE: main.scala
```scala
...
```

### FILE: main.test.scala
```scala
...
```
