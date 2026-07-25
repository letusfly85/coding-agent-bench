Complete the following Scala 3 http4s REST API. The scaffolding compiles as given;
your job is to fill in the route logic and write the tests.

**Note:** this variant hands the model a working skeleton, so it is a strictly easier
task than the other variants. It exists to separate "cannot set up the stack" from
"cannot write the route logic".

## Skeleton

This is `main.scala` as it stands. Replace every `???` with a real implementation.
Do not change the imports, the dependency directives, or the signatures.

```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._

case class Task(id: Long, title: String, done: Boolean)
case class CreateTaskReq(title: String)
case class UpdateTaskReq(title: String, done: Boolean)

object Api:
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" => ???
      case GET -> Root / "tasks" => ???
      case req @ POST -> Root / "tasks" => ???
      case GET -> Root / "tasks" / LongVar(id) => ???
      case req @ PUT -> Root / "tasks" / LongVar(id) => ???
      case DELETE -> Root / "tasks" / LongVar(id) => ???
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

object Main extends IOApp.Simple:
  val run: IO[Unit] =
    Api.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .useForever
    }
```

## Behaviour

| Method | Path | Behaviour |
|---|---|---|
| GET | `/health` | 200, body `{"status":"ok"}` |
| GET | `/tasks` | 200, JSON array of all tasks, ordered by ascending id |
| POST | `/tasks` | Body `{"title":"..."}`. Creates a task with `done=false`, id starting at 1 and incrementing. Returns 201 with the created task. |
| GET | `/tasks/{id}` | 200 with the task, or 404 if absent |
| PUT | `/tasks/{id}` | Body `{"title":"...","done":true}`. Returns 200 with the updated task, or 404 if absent. |
| DELETE | `/tasks/{id}` | 204 if deleted, 404 if absent |

Note that `Ref.updateAndGet` returns the value **after** the update, so it cannot tell
you whether a key existed before a delete. `Ref.modify` returns both the new state and
a value of your choosing.

## Tests

Put tests in `main.test.scala` using **munit**. Drive the `HttpApp[IO]` directly by
constructing `Request[IO]` values and calling `.run(...)` — do **not** bind a real TCP
port. Cover at minimum:

1. `GET /health` returns 200
2. `POST /tasks` returns 201 and id 1
3. `GET /tasks/1` after creation returns the task
4. `GET /tasks/999` returns 404
5. `DELETE` an existing task returns 204, and a subsequent `GET` returns 404

## Output format

Output **only** the files, each introduced by a `### FILE: <relative path>` line followed
by a single fenced code block. No commentary before, between, or after the files.

### FILE: main.scala
```scala
...
```

### FILE: main.test.scala
```scala
...
```
