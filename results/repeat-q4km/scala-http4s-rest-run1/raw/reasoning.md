The user wants a Scala 3 REST API using http4s and scala-cli.
I need to output exactly two files: `main.scala` and `main.test.scala`.
Constraints:
- Scala 3.3.4
- Dependencies exactly as specified.
- Data model: `Task(id: Long, title: String, done: Boolean)`
- Endpoints: GET /health, GET /tasks, POST /tasks, GET /tasks/{id}, PUT /tasks/{id}, DELETE /tasks/{id}
- Use `cats.effect.IO`, `HttpRoutes`, `Ref` for state.
- `routes(store, counter): HttpRoutes[IO]`
- `freshApp: IO[HttpApp[IO]]`
- Circe codecs for Task.
- `object Main extends IOApp.Simple` on port 3000.
- Tests in `main.test.scala` using munit, driving `HttpApp[IO]` directly, no real TCP port.
- Tests cover specific scenarios.

Let's design `main.scala`:

Imports:
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server._
import org.http4s.implicits._
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)

object TaskApi {
  type Store = Map[Long, Task]

  def routes(
    store: Ref[IO, Store],
    counter: Ref[IO, Long]
  ): HttpRoutes[IO] = {
    val r = new Http4sDsl[IO] {}
    import r._

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok("""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id)).flatMap { tasks =>
          Ok(tasks.asJson)
        }

      case req @ POST -> Root / "tasks" =>
        req.as[io.circe.Json].flatMap { json =>
          json.as[String].flatMap { title =>
            counter.updateAndGet(_ + 1).flatMap { id =>
              val task = Task(id, title, done = false)
              store.update(_.updated(id, task)) >> Created(task)
            }
          }.getOrElse(
            BadRequest("Invalid JSON body, expected {\"title\": \"...\"}")
          )
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { s =>
          s.get(id) match {
            case Some(task) => Ok(task)
            case None       => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[Task].flatMap { updatedTask =>
          store.get.flatMap { s =>
            if (s.contains(id)) {
              val taskWithId = updatedTask.copy(id = id)
              store.update(_.updated(id, taskWithId)) >> Ok(taskWithId)
            } else {
              NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { prev =>
          if (prev.contains(id)) NoContent()
          else NotFound()
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] =
    (Ref.of[IO, Store](Map.empty), Ref.of[IO, Long](0L)).mapN { (store, counter) =>
      routes(store, counter).orNotFound
    }
}

object Main extends IOApp.Simple {
  val run: IO[ExitCode] =
    TaskApi.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost("0.0.0.0")
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_ => IO.never)
        .as(ExitCode.Success)
    }
}
```

Wait, `req.as[io.circe.Json]` for POST might be tricky if the body is just `{"title":"..."}`. Actually, `req.as[io.circe.Json]` is fine, then extract title. Or use a case class `TaskInput(title: String)`. Let's stick to simple extraction or use a wrapper.
Better: define `case class TaskInput(title: String)` and use `req.as[TaskInput]`. That's cleaner.

Let's adjust:
```scala
case class TaskInput(title: String)

// in routes:
case req @ POST -> Root / "tasks" =>
  req.as[TaskInput].flatMap { input =>
    counter.updateAndGet(_ + 1).flatMap { id =>
      val task = Task(id, input.title, done = false)
      store.update(_.updated(id, task)) >> Created(task)
    }
  }
```

Check imports: `org.http4s.dsl.io._` provides `GET`, `POST`, etc. `Root`, `LongVar` are from `org.http4s.syntax.KleisliSyntax` or similar? Actually, `Root` is in `org.http4s.Uri._` or `org.http4s.syntax.UriSyntax`. I'll import `org.http4s.syntax._` or specifically `org.http4s.Uri._`. Actually, in http4s 0.23, `Root` is in `org.http4s.Uri._`. Let's import `org.http4s.Uri._`.

Let's verify http4s 0.23.30 DSL usage:
```scala
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.syntax._
import org.http4s.Uri._
```
Actually, `Root` is available via `org.http4s.Uri._`.
`LongVar` is in `org.http4s.dsl.impl._`.

Let's refine imports:
```scala
import cats.effect.{IO, IOApp, Ref}
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.dsl.impl._
import org.http4s.ember.server._
import org.http4s.implicits._
import org.http4s.syntax._
import org.http4s.Uri._
import io.circe.generic.auto._
import io.circe.syntax._
```

Wait, `HttpRoutes.of[IO] { case ... }` requires pattern matching syntax. In http4s 0.23, it's:
```scala
HttpRoutes.of[IO] {
  case GET -> Root / "health" => ...
}
```
This works.

Let's check `req.as[TaskInput]`: needs `EntityDecoder[IO, TaskInput]` from circe. `io.circe.generic.auto._` provides it.

One detail: `counter.updateAndGet(_ + 1)` is correct for Ref.

Now `freshApp`:
```scala
def freshApp: IO[HttpApp[IO]] =
  (Ref.of[IO, Store](Map.empty), Ref.of[IO, Long](0L)).mapN { (store, counter) =>
    routes(store, counter).orNotFound
  }
```
This matches requirement.

`Main`:
```scala
object Main extends IOApp.Simple {
  val run: IO[ExitCode] =
    TaskApi.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost("0.0.0.0")
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_ => IO.never)
        .as(ExitCode.Success)
    }
}
```
Matches.

Now `main.test.scala`:
Requirements:
- munit
- Drive `HttpApp[IO]` directly
- No real TCP port
- Cover:
  1. GET /health returns 200
  2. POST /tasks returns 201 and id 1
  3. GET /tasks/1 after creation returns the task
  4. GET /tasks/999 returns 404
  5. DELETE an existing task returns 204, and a subsequent GET returns 404

We'll use `TaskApi.freshApp` to get an isolated app for each test or group.
Since tests run concurrently, each test should get its own app or we run sequentially. Munit supports `IO` tests via `munit-cats-effect-3`, but the prompt only specifies `org.scalameta::munit::1.0.4`. So we must use synchronous tests or block on `IO.unsafeRunSync()`. I'll use `IO.unsafeRunSync()` inside tests.

Wait, munit 1.0.4 doesn't have async support by default. I'll just call `.unsafeRunSync()` on each `IO`.

Let's draft `main.test.scala`:

```scala
//> using test.dep org.scalameta::munit::1.0.4

import munit.FunSuite
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.syntax._
import org.http4s.Uri._
import io.circe.parser._
import io.circe.syntax._
import cats.effect.unsafe.implicits.global

class TaskApiTests extends FunSuite {

  test("GET /health returns 200") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val req = Request[IO](GET, uri"/health")
    val resp = app(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
  }

  test("POST /tasks returns 201 and id 1") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val body = """{"title":"Buy milk"}"""
    val req = Request[IO](
      method = POST,
      uri = uri"/tasks",
      entity = body
    )
    val resp = app(req).unsafeRunSync()
    assertEquals(resp.status, Status.Created)
    val json = decode[Task](resp.bodyText.unsafeRunSync())
    assertEquals(json.right.get.id, 1L)
    assertEquals(json.right.get.title, "Buy milk")
  }

  test("GET /tasks/1 after creation returns the task") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val createReq = Request[IO](
      method = POST,
      uri = uri"/tasks",
      entity = """{"title":"Test task"}"""
    )
    app(createReq).unsafeRunSync()

    val getReq = Request[IO](GET, uri"/tasks/1")
    val getResp = app(getReq).unsafeRunSync()
    assertEquals(getResp.status, Status.Ok)
    val task = decode[Task](getResp.bodyText.unsafeRunSync()).right.get
    assertEquals(task.id, 1L)
    assertEquals(task.title, "Test task")
  }

  test("GET /tasks/999 returns 404") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val req = Request[IO](GET, uri"/tasks/999")
    val resp = app(req).unsafeRunSync()
    assertEquals(resp.status, Status.NotFound)
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val createReq = Request[IO](
      method = POST,
      uri = uri"/tasks",
      entity = """{"title":"To delete"}"""
    )
    app(createReq).unsafeRunSync()

    val deleteReq = Request[IO](DELETE, uri"/tasks/1")
    val deleteResp = app(deleteReq).unsafeRunSync()
    assertEquals(deleteResp.status, Status.NoContent)

    val getReq = Request[IO](GET, uri"/tasks/1")
    val getResp = app(getReq).unsafeRunSync()
    assertEquals(getResp.status, Status.NotFound)
  }
}
```

Wait, `resp.bodyText.unsafeRunSync()` might be deprecated or not exist in 0.23.30. In http4s 0.23, it's `resp.bodyText` which returns `IO[String]`. So `resp.bodyText.unsafeRunSync()` is correct.
Actually, `EntityDecoder.jsonOf[IO, Task]` exists via circe integration. But I'm using `decode[Task](...)` from `io.circe.parser._`. That's fine.

Let's verify imports in test file:
```scala
import munit.FunSuite
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.syntax._
import org.http4s.Uri._
import io.circe.parser._
import cats.effect.unsafe.implicits.global
```
`decode[Task]` needs `Task` in scope. I'll import it or put it in the same package. Since scala-cli treats each file independently unless in same package, I'll define `Task` in `main.scala` and import it in tests, or just duplicate it? Better to define in `main.scala` and import in `main.test.scala` if they share package, but scala-cli default is no package. I'll just put `Task` and `TaskInput` in `main.scala`, and in `main.test.scala` I'll import them or define them again? Actually, scala-cli compiles all `.scala` files together in the same virtual project, so they share the same namespace if no package is declared. So `Task` will be visible.

Wait, `io.circe.parser._` provides `decode`. I'll use that.

One minor thing: `resp.bodyText` might throw if not consumed properly? It's fine for tests.

Let's double check the `POST /tasks` body parsing in `main.scala`:
```scala
case req @ POST -> Root / "tasks" =>
  req.as[TaskInput].flatMap { input =>
    counter.updateAndGet(_ + 1).flatMap { id =>
      val task = Task(id, input.title, done = false)
      store.update(_.updated(id, task)) >> Created(task)
    }
  }
```
This relies on `EntityDecoder[IO, TaskInput]` from circe. `io.circe.generic.auto._` provides it. It's correct.

Check `GET /tasks`:
```scala
case GET -> Root / "tasks" =>
  store.get.map(_.values.toSeq.sortBy(_.id)).flatMap { tasks =>
    Ok(tasks.asJson)
  }
```
Correct.

Check `PUT /tasks/{id}`:
```scala
case req @ PUT -> Root / "tasks" / LongVar(id) =>
  req.as[Task].flatMap { updatedTask =>
    store.get.flatMap { s =>
      if (s.contains(id)) {
        val taskWithId = updatedTask.copy(id = id)
        store.update(_.updated(id, taskWithId)) >> Ok(taskWithId)
      } else {
        NotFound()
      }
    }
  }
```
Correct.

Check `DELETE /tasks/{id}`:
```scala
case DELETE -> Root / "tasks" / LongVar(id) =>
  store.updateAndGet(_. - id).flatMap { prev =>
    if (prev.contains(id)) NoContent()
    else NotFound()
  }
```
Correct.

Check `GET /health`:
```scala
case GET -> Root / "health" =>
  Ok("""{"status":"ok"}""")
```
Correct.

Now, verify `HttpRoutes.of[IO]` syntax with pattern matching. In http4s 0.23, it's:
```scala
HttpRoutes.of[IO] {
  case GET -> Root / "health" => Ok(...)
}
```
This is valid.

One thing: `Root` is in `org.http4s.Uri._`. I'll import that.
`LongVar` is in `org.http4s.dsl.impl._`. I'll import that.
`GET`, `POST`, etc. are in `org.http4s.dsl.io._` or `org.http4s.Method._`. `dsl.io._` provides them.
`Ok`, `Created`, `NotFound`, `NoContent`, `BadRequest` are in `org.http4s.Status._` or `org.http4s.dsl.io._`. `dsl.io._` provides them via `Status._` import or directly. Actually, `dsl.io._` imports `Status._` and `Response._` helpers. It's fine.

Let's assemble `main.scala` carefully.

```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.dsl.impl._
import org.http4s.ember.server._
import org.http4s.implicits._
import org.http4s.syntax._
import org.http4s.Uri._
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)
case class TaskInput(title: String)

object TaskApi {
  type Store = Map[Long, Task]

  def routes(
    store: Ref[IO, Store],
    counter: Ref[IO, Long]
  ): HttpRoutes[IO] = {
    val r = new Http4sDsl[IO] {}
    import r._

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok("""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id)).flatMap { tasks =>
          Ok(tasks.asJson)
        }

      case req @ POST -> Root / "tasks" =>
        req.as[TaskInput].flatMap { input =>
          counter.updateAndGet(_ + 1).flatMap { id =>
            val task = Task(id, input.title, done = false)
            store.update(_.updated(id, task)) >> Created(task)
          }
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { s =>
          s.get(id) match {
            case Some(task) => Ok(task)
            case None       => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[Task].flatMap { updatedTask =>
          store.get.flatMap { s =>
            if (s.contains(id)) {
              val taskWithId = updatedTask.copy(id = id)
              store.update(_.updated(id, taskWithId)) >> Ok(taskWithId)
            } else {
              NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { prev =>
          if (prev.contains(id)) NoContent()
          else NotFound()
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] =
    (Ref.of[IO, Store](Map.empty), Ref.of[IO, Long](0L)).mapN { (store, counter) =>
      routes(store, counter).orNotFound
    }
}

object Main extends IOApp.Simple {
  val run: IO[ExitCode] =
    TaskApi.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost("0.0.0.0")
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_ => IO.never)
        .as(ExitCode.Success)
    }
}
```

Check `Http4sDsl[IO]` usage: In 0.23, `new Http4sDsl[IO] {}` is correct, but often people just import `org.http4s.dsl.io._` which brings DSL methods into scope without needing `new Http4sDsl`. Actually, `dsl.io._` provides extension methods and implicits, but pattern matching DSL (`GET -> Root / "path"`) works via `HttpRoutes.of` without `Http4sDsl`. I'll remove `val r = new Http4sDsl[IO] {}` to keep it clean, as it's not strictly needed for this style. The methods `Ok`, `Created`, etc. are from `Status._` or `Response._` which are imported via `dsl.io._` or `http4s._`. Actually, `dsl.io._` imports `Status._` and `Response._` helpers. I'll keep imports minimal.

Let's verify imports for `dsl.io._`:
It provides `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OK`, `Created`, etc. as extension methods or vals.
Actually, in 0.23, `GET` etc. are in `org.http4s.Method._`. `dsl.io._` might not export them directly. I'll explicitly import `org.http4s.Method._` and `org.http4s.Status._` and `org.http4s.Response._` to be safe.

Revised imports:
```scala
import cats.effect.{IO, IOApp, Ref}
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.dsl.impl._
import org.http4s.ember.server._
import org.http4s.implicits._
import org.http4s.syntax._
import org.http4s.Uri._
import org.http4s.Method._
import org.http4s.Status._
import io.circe.generic.auto._
import io.circe.syntax._
```
This is robust.

Now `main.test.scala`:
```scala
//> using test.dep org.scalameta::munit::1.0.4

import munit.FunSuite
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.syntax._
import org.http4s.Uri._
import org.http4s.Method._
import org.http4s.Status._
import io.circe.parser._
import cats.effect.unsafe.implicits.global

class TaskApiTests extends FunSuite {

  test("GET /health returns 200") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val req = Request[IO](GET, uri"/health")
    val resp = app(req).unsafeRunSync()
    assertEquals(resp.status, Ok)
  }

  test("POST /tasks returns 201 and id 1") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val body = """{"title":"Buy milk"}"""
    val req = Request[IO](
      method = POST,
      uri = uri"/tasks",
      entity = body
    )
    val resp = app(req).unsafeRunSync()
    assertEquals(resp.status, Status.Created)
    val json = decode[Task](resp.bodyText.unsafeRunSync())
    assertEquals(json.right.get.id, 1L)
    assertEquals(json.right.get.title, "Buy milk")
  }

  test("GET /tasks/1 after creation returns the task") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val createReq = Request[IO](
      method = POST,
      uri = uri"/tasks",
      entity = """{"title":"Test task"}"""
    )
    app(createReq).unsafeRunSync()

    val getReq = Request[IO](GET, uri"/tasks/1")
    val getResp = app(getReq).unsafeRunSync()
    assertEquals(getResp.status, Ok)
    val task = decode[Task](getResp.bodyText.unsafeRunSync()).right.get
    assertEquals(task.id, 1L)
    assertEquals(task.title, "Test task")
  }

  test("GET /tasks/999 returns 404") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val req = Request[IO](GET, uri"/tasks/999")
    val resp = app(req).unsafeRunSync()
    assertEquals(resp.status, NotFound)
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val createReq = Request[IO](
      method = POST,
      uri = uri"/tasks",
      entity = """{"title":"To delete"}"""
    )
    app(createReq).unsafeRunSync()

    val deleteReq = Request[IO](DELETE, uri"/tasks/1")
    val deleteResp = app(deleteReq).unsafeRunSync()
    assertEquals(deleteResp.status, NoContent)

    val getReq = Request[IO](GET, uri"/tasks/1")
    val getResp = app(getReq).unsafeRunSync()
    assertEquals(getResp.status, NotFound)
  }
}
```

Check `decode[Task]` visibility: `Task` is defined in `main.scala`. Since scala-cli compiles all `.scala` files in the same project, it's visible. If not, I can import it or define it locally, but usually it's fine. To be strictly safe, I'll add `import TaskApi._` or just rely on same-package visibility. I'll assume same namespace.

One detail: `uri"/health"` syntax requires `org.http4s.syntax._` or `Uri._`. I imported both.
`resp.bodyText` is `IO[String]`. `unsafeRunSync()` is correct.
`cats.effect.unsafe.implicits.global` provides `unsafeRunSync`.

All constraints met:
- Scala 3.3.4
- Exact deps
- Data model matches
- Endpoints match
- `cats.effect.IO`, `HttpRoutes`, `Ref` used
- `routes(store, counter)` and `freshApp` exposed
- Circe codecs derived
- `Main` extends `IOApp.Simple`, port 3000
- Tests in `main.test.scala` using munit, direct `HttpApp[IO]`, no TCP, covers 5 cases.

One minor point: `HttpRoutes.of[IO]` pattern matching might need `import org.http4s.dsl.io._` for `GET`, etc. I have it.
Also `LongVar` is in `org.http4s.dsl.impl._`. I have it.
`Root` is in `org.http4s.Uri._`. I have it.

Let's verify `HttpRoutes.of[IO]` syntax in 0.23.30:
It's `HttpRoutes.of[IO] { case GET -> Root / "health" => ... }`. This is standard.

Everything looks solid.

I will generate the output exactly as requested.
One thing: `//> using test.dep org.scalameta::munit::1.0.4` is in `main.scala` as per prompt. The prompt says "Put the dependency directives at the top of the main file". So `main.test.scala` doesn't need them, but I'll leave the directive in `main.scala` only.

Final check of the prompt: "Put the dependency directives at the top of the main file, using exactly these:"
So `main.scala` gets them.

I will produce the two files.
Code structure:
main.scala:
- directives
- imports
- Task, TaskInput
- TaskApi object with routes, freshApp
- Main object

main.test.scala:
- imports
- TaskApiTests class

All good.
Proceed. 
One minor adjustment: `decode[Task]` might complain if `Task` isn't imported. I'll add `import TaskApi._` or just `import _root_.Task` if needed, but usually same file/project works. I'll just add `import TaskApi._` in tests to be safe, or define `Task` in a shared place. Actually, scala-cli compiles all `.scala` files together, so `Task` is in the same package (empty). It's fine.

I'll output exactly as requested.
Done. 
Let's generate. 
Self-Correction/Verification during thought:
- `HttpRoutes.of[IO]` pattern matching requires `import org.http4s.dsl.io._`? Actually, `GET` etc are in `Method._`. `dsl.io._` provides DSL helpers but not necessarily the vals. I'll import `Method._` explicitly.
- `LongVar` is in `dsl.impl._`.
- `Root` is in `Uri._`.
- `Ok`, `Created`, etc. are in `Status._`.
- `Request[IO]` constructor: `Request(method, uri, entity = ...)` works.
- `resp.bodyText` is available via `EntityDecoder` or extension. In 0.23, it's `resp.bodyText` from `org.http4s.EntityDecoder` or `ResponseSyntax`. I imported `syntax._`.
- All good.

Output matches requirements.
Proceeds. 
Generating. 
[Output Generation]
```scala
// main.scala content
// main.test.scala content
```
Matches format.
Done. 
(Self-Correction/Note during generation prep)
Wait, `HttpRoutes.of[IO] { case GET -> ... }` might require `import org.http4s.dsl.io._` for the `GET` pattern matcher syntax? Actually, `GET` is just `Method.GET`. The pattern matching syntax `GET -> Root / "path"` is provided by `HttpRoutes` companion or DSL. It's fine.

One thing: `req.as[TaskInput]` might fail if JSON is malformed, returning `DecodeFailure`. `HttpRoutes` handles it as `BadRequest`. That's correct.

All set.
Output matches exactly.
Proceeds. 
[Final Check of the Prompt]
"Output only the files, each introduced by a `### FILE: <relative path>` line followed by a single fenced code block. No commentary before, between, or after the files."
Got it.
Generating. 
[Output Generation] -> *Proceeds*
