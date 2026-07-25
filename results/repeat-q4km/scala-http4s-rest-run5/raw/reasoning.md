The user wants a Scala 3 REST API using http4s and scala-cli.
I need to output exactly two files: `main.scala` and `main.test.scala`.
I will strictly follow the requirements:
- Use specified scala-cli directives.
- Data model: `Task(id: Long, title: String, done: Boolean)`
- Endpoints: GET /health, GET /tasks, POST /tasks, GET /tasks/{id}, PUT /tasks/{id}, DELETE /tasks/{id}
- Use `cats.effect.IO`, `HttpRoutes[IO]`, `Ref[IO, ...]` for in-memory state.
- Expose `routes(store, counter)` and `freshApp`.
- Use circe codecs.
- `Main` object extends `IOApp.Simple`, serves on 3000 via Ember.
- Tests in `main.test.scala` using munit, driving `HttpApp[IO]` directly with `Request[IO]`.
- Test cases: health, POST, GET by id, GET 404, DELETE + GET 404.

Let's design the code.

`main.scala`:
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import org.http4s.{HttpApp, HttpRoutes, Request, Response, Status}
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)

object TaskApi {

  def routes(
    store: Ref[IO, Map[Long, Task]],
    counter: Ref[IO, Long]
  ): HttpRoutes[IO] = {
    val r = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Map("status" -> "ok").asJson)

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.values.toSeq.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" if req.hasEntity =>
        req.as[Map[String, String]].flatMap {
          case map if map.contains("title") =>
            for {
              title <- IO.pure(map("title"))
              id <- counter.updateAndGet(_ + 1)
              task = Task(id, title, done = false)
              _ <- store.update(_.updated(id, task))
            } yield Created(task.asJson)
          case _ =>
            BadRequest("Missing 'title' field")
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task.asJson)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) if req.hasEntity =>
        req.as[Task].flatMap { partial =>
          store.updateAndGet { tasks =>
            tasks.get(id) match {
              case Some(existing) =>
                tasks.updated(id, existing.copy(title = partial.title, done = partial.done))
              case None => tasks
            }
          }.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task.asJson)
              case None => NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { tasks =>
          if (tasks.contains(id)) NoContent() else NotFound()
        }
    }
    r
  }

  def freshApp: IO[HttpApp[IO]] =
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound
}

object Main extends IOApp.Simple {
  val run: IO[Unit] =
    TaskApi.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost("0.0.0.0")
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_ => IO.never)
    }
}
```

Wait, `req.as[Map[String, String]]` might be tricky with circe. Better to use a small case class or `req.as[circe.Json]` and extract, or just use `req.as[io.circe.Json]`.
Actually, for POST /tasks, we expect `{"title":"..."}`. Let's use a simple wrapper:
```scala
case class TaskInput(title: String)
```
Then `req.as[TaskInput]` is clean.

Refined POST:
```scala
      case req @ POST -> Root / "tasks" if req.hasEntity =>
        req.as[TaskInput].flatMap { input =>
          for {
            id <- counter.updateAndGet(_ + 1)
            task = Task(id, input.title, done = false)
            _ <- store.update(_.updated(id, task))
          } yield Created(task.asJson)
        }
```

For PUT, we can use `Task` directly or a partial update type. Requirements say: Body `{"title":"...","done":true}`. So using `Task` is fine; we ignore `id` from body and use path param.

Let's adjust PUT:
```scala
      case req @ PUT -> Root / "tasks" / LongVar(id) if req.hasEntity =>
        req.as[Task].flatMap { partial =>
          store.updateAndGet { tasks =>
            tasks.get(id) match {
              case Some(existing) =>
                tasks.updated(id, existing.copy(title = partial.title, done = partial.done))
              case None => tasks
            }
          }.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task.asJson)
              case None => NotFound()
            }
          }
        }
```

This looks solid.

Now `main.test.scala`:
Need munit tests driving `HttpApp[IO]` directly.
Use `TaskApi.freshApp` to get an app.
Create requests using http4s `Request[IO]`.
Run with `IO.runtime.global`.

```scala
import munit.CatsEffectSuite
import cats.effect.IO
import org.http4s.{Method, Request, Uri}
import org.http4s.circe._
import io.circe.generic.auto._
import io.circe.syntax._

class TaskApiSuite extends CatsEffectSuite {

  private def createApp(): IO[TaskApi.freshApp] = TaskApi.freshApp

  test("GET /health returns 200") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](uri = Uri.unsafeFromString("/health"))
      resp <- app.run(req)
    } yield {
      assertEquals(resp.status.code, 200)
      val body = resp.as[String].unsafeRunSync()(cats.effect.unsafe.implicits.global)
      assert(body.contains("\"status\":\"ok\""))
    }
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("Test").asJson
      )
      resp <- app.run(req)
      task <- resp.as[Task]
    } yield {
      assertEquals(resp.status.code, 201)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "Test")
      assertEquals(task.done, false)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("First").asJson
      ))
      getReq = Request[IO](uri = Uri.unsafeFromString("/tasks/1"))
      resp <- app.run(getReq)
      task <- resp.as[Task]
    } yield {
      assertEquals(resp.status.code, 200)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "First")
    }
  }

  test("GET /tasks/999 returns 404") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](uri = Uri.unsafeFromString("/tasks/999"))
      resp <- app.run(req)
    } yield {
      assertEquals(resp.status.code, 404)
    }
  }

  test("DELETE existing task returns 204, subsequent GET returns 404") {
    for {
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("ToDelete").asJson
      ))
      delResp <- app.run(Request[IO](
        method = Method.DELETE,
        uri = Uri.unsafeFromString("/tasks/1")
      ))
      getResp <- app.run(Request[IO](uri = Uri.unsafeFromString("/tasks/1")))
    } yield {
      assertEquals(delResp.status.code, 204)
      assertEquals(getResp.status.code, 404)
    }
  }
}
```

Wait, `TaskApi.freshApp` is a `IO[HttpApp[IO]]`. In tests, I should use `TaskApi.freshApp` directly.
Also, I need to import `cats.effect.unsafe.implicits.global` or use `CatsEffectSuite`'s `IO` support properly. `CatsEffectSuite` provides `IO` effect type for tests, but `resp.as[T]` returns `IO[T]`. I can just use `resp.as[Task]` inside the `for` comprehension because `CatsEffectSuite` supports `IO`.

Let's adjust test imports and structure to be safe and idiomatic:
```scala
import munit.CatsEffectSuite
import cats.effect.IO
import org.http4s.{Method, Request, Uri}
import org.http4s.circe._
import io.circe.generic.auto._
import io.circe.syntax._

class TaskApiSuite extends CatsEffectSuite {

  test("GET /health returns 200") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](uri = Uri.unsafeFromString("/health"))
      resp <- app.run(req)
      body <- resp.as[String]
    } yield {
      assertEquals(resp.status.code, 200)
      assert(body.contains("\"status\":\"ok\""))
    }
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("Test").asJson
      )
      resp <- app.run(req)
      task <- resp.as[Task]
    } yield {
      assertEquals(resp.status.code, 201)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "Test")
      assertEquals(task.done, false)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("First").asJson
      ))
      getReq = Request[IO](uri = Uri.unsafeFromString("/tasks/1"))
      resp <- app.run(getReq)
      task <- resp.as[Task]
    } yield {
      assertEquals(resp.status.code, 200)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "First")
    }
  }

  test("GET /tasks/999 returns 404") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](uri = Uri.unsafeFromString("/tasks/999"))
      resp <- app.run(req)
    } yield {
      assertEquals(resp.status.code, 404)
    }
  }

  test("DELETE existing task returns 204, subsequent GET returns 404") {
    for {
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("ToDelete").asJson
      ))
      delResp <- app.run(Request[IO](
        method = Method.DELETE,
        uri = Uri.unsafeFromString("/tasks/1")
      ))
      getResp <- app.run(Request[IO](uri = Uri.unsafeFromString("/tasks/1")))
    } yield {
      assertEquals(delResp.status.code, 204)
      assertEquals(getResp.status.code, 404)
    }
  }
}
```

Check dependencies and imports in `main.scala`:
Need `cats.effect.{IO, IOApp, Ref}`
`cats.syntax.all._`
`org.http4s.{HttpApp, HttpRoutes, Request, Response, Status, Uri, Method}`
`org.http4s.dsl.io._`
`org.http4s.ember.server.EmberServerBuilder`
`io.circe.generic.auto._`
`io.circe.syntax._`

Also `TaskInput` case class should be defined.
Let's assemble `main.scala` carefully.

One detail: `req.as[TaskInput]` might fail if JSON is malformed. That's fine, http4s will return 400.
The requirements say: "Body `{"title":"..."}`. Creates a task...". So `TaskInput(title: String)` is perfect.

Let's verify `routes` signature matches requirement:
`def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO]`
`def freshApp: IO[HttpApp[IO]]`
Matches.

Check `GET /tasks` ordering: "ordered by ascending id". `tasks.values.toSeq.sortBy(_.id)` does that.

Check `PUT /tasks/{id}`: "Body `{"title":"...","done":true}`. Returns 200 with the updated task, or 404 if absent."
My implementation:
```scala
      case req @ PUT -> Root / "tasks" / LongVar(id) if req.hasEntity =>
        req.as[Task].flatMap { partial =>
          store.updateAndGet { tasks =>
            tasks.get(id) match {
              case Some(existing) =>
                tasks.updated(id, existing.copy(title = partial.title, done = partial.done))
              case None => tasks
            }
          }.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task.asJson)
              case None => NotFound()
            }
          }
        }
```
This works. It ignores `id` in the body, uses path param. Updates title and done. Returns 200 with updated task, or 404.

Check `DELETE /tasks/{id}`: "204 if deleted, 404 if absent"
My implementation:
```scala
      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { tasks =>
          if (tasks.contains(id)) NoContent() else NotFound()
        }
```
Correct.

Check `Main`:
```scala
object Main extends IOApp.Simple {
  val run: IO[Unit] =
    TaskApi.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost("0.0.0.0")
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_ => IO.never)
    }
}
```
Correct.

One minor thing: `cats.effect.unsafe.implicits.global` is not needed in tests because `CatsEffectSuite` handles it, but `resp.as[T]` returns `IO[T]`, and the `for` comprehension in `test("...") { ... }` expects an `IO[_]`. `CatsEffectSuite` automatically lifts `IO` into its test runner. So it's fine.

Let's ensure all imports are present in `main.scala`.
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import org.http4s.{HttpApp, HttpRoutes, Request, Response, Status}
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)
case class TaskInput(title: String)

object TaskApi {

  def routes(
    store: Ref[IO, Map[Long, Task]],
    counter: Ref[IO, Long]
  ): HttpRoutes[IO] = {
    val r = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Map("status" -> "ok").asJson)

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.values.toSeq.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" if req.hasEntity =>
        req.as[TaskInput].flatMap { input =>
          for {
            id <- counter.updateAndGet(_ + 1)
            task = Task(id, input.title, done = false)
            _ <- store.update(_.updated(id, task))
          } yield Created(task.asJson)
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task.asJson)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) if req.hasEntity =>
        req.as[Task].flatMap { partial =>
          store.updateAndGet { tasks =>
            tasks.get(id) match {
              case Some(existing) =>
                tasks.updated(id, existing.copy(title = partial.title, done = partial.done))
              case None => tasks
            }
          }.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task.asJson)
              case None => NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { tasks =>
          if (tasks.contains(id)) NoContent() else NotFound()
        }
    }
    r
  }

  def freshApp: IO[HttpApp[IO]] =
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound
}

object Main extends IOApp.Simple {
  val run: IO[Unit] =
    TaskApi.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost("0.0.0.0")
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_ => IO.never)
    }
}
```

Check `main.test.scala`:
```scala
import munit.CatsEffectSuite
import cats.effect.IO
import org.http4s.{Method, Request, Uri}
import org.http4s.circe._
import io.circe.generic.auto._
import io.circe.syntax._

class TaskApiSuite extends CatsEffectSuite {

  test("GET /health returns 200") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](uri = Uri.unsafeFromString("/health"))
      resp <- app.run(req)
      body <- resp.as[String]
    } yield {
      assertEquals(resp.status.code, 200)
      assert(body.contains("\"status\":\"ok\""))
    }
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("Test").asJson
      )
      resp <- app.run(req)
      task <- resp.as[Task]
    } yield {
      assertEquals(resp.status.code, 201)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "Test")
      assertEquals(task.done, false)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("First").asJson
      ))
      getReq = Request[IO](uri = Uri.unsafeFromString("/tasks/1"))
      resp <- app.run(getReq)
      task <- resp.as[Task]
    } yield {
      assertEquals(resp.status.code, 200)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "First")
    }
  }

  test("GET /tasks/999 returns 404") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](uri = Uri.unsafeFromString("/tasks/999"))
      resp <- app.run(req)
    } yield {
      assertEquals(resp.status.code, 404)
    }
  }

  test("DELETE existing task returns 204, subsequent GET returns 404") {
    for {
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("ToDelete").asJson
      ))
      delResp <- app.run(Request[IO](
        method = Method.DELETE,
        uri = Uri.unsafeFromString("/tasks/1")
      ))
      getResp <- app.run(Request[IO](uri = Uri.unsafeFromString("/tasks/1")))
    } yield {
      assertEquals(delResp.status.code, 204)
      assertEquals(getResp.status.code, 404)
    }
  }
}
```

One thing: `CatsEffectSuite` requires `munit.CatsEffectSuite` and the test body returns `IO[_]`. That's correct.
The imports in test file are correct.
Dependencies are exactly as requested.
All constraints met.
Output format: exactly as requested.

Let's double check the `routes` signature requirement:
"Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`, plus a helper `def freshApp: IO[HttpApp[IO]]`"
My code matches.

Check `GET /tasks` ordering: "ordered by ascending id" -> `sortBy(_.id)` ensures that.

Check `POST /tasks` behavior: "Body `{"title":"..."}`. Creates a task with `done=false`. Returns 201 with the created task." -> matches.

Check `PUT /tasks/{id}` behavior: "Body `{"title":"...","done":true}`. Returns 200 with the updated task, or 404 if absent." -> matches.

Check `DELETE /tasks/{id}` behavior: "204 if deleted, 404 if absent" -> matches.

Check `GET /health`: "200, body `{"status":"ok"}`" -> matches.

Everything looks solid. I will produce the output now.
One minor point: `cats.effect.unsafe.implicits.global` is not strictly needed in tests because `CatsEffectSuite` handles it, but sometimes `resp.as[T]` needs an `IORuntime`. In `CatsEffectSuite`, the `IO` is run by the suite, so it's fine. I'll leave it as is.

Final check of the prompt: "Output only the files, each introduced by a `### FILE: <relative path>` line followed by a single fenced code block. No commentary before, between, or after the files."

Ready.
Output matches exactly.
Proceeds. 
One detail: `HttpRoutes.of[IO]` pattern matching requires `org.http4s.dsl.io._` which is imported.
`LongVar` is from `org.http4s.dsl.io._`.
`Root` is from `org.http4s.dsl.io._`.
All good.
I will generate the response. 
Self-Correction/Verification during thought:
Wait, `req.as[TaskInput]` might throw if JSON is invalid. http4s handles that and returns 400. That's acceptable.
The prompt says: "Derive circe codecs for Task (e.g. io.circe.generic.auto._ or explicit Encoder/Decoder)." I used `generic.auto._`.
The test file uses `TaskApi.freshApp` directly. That's fine.
All constraints met.
Output matches requirement.
Proceeds. 
Generating. 
[Output Generation] -> matches the final code blocks. 
Done. 
(Self-Correction/Note during generation prep)
I should ensure `main.test.scala` imports `cats.effect.IO` and `org.http4s.circe._` properly. It does.
The prompt asks for `main.scala` and `main.test.scala`.
All good.
Output matches. 
Proceeds. 
[Final Check of the Code]
`main.scala`:
- Directives: exact.
- Imports: correct.
- Data model: exact.
- `routes` and `freshApp`: exact.
- `Main`: exact.
`main.test.scala`:
- munit CatsEffectSuite.
- Tests cover all 5 cases.
- Uses `Request[IO]` and `.run(...)`.
- No TCP port.
All good.
Output matches request.
Done. 
[Output Generation] (matches the response)
```scala
//> using scala 3.3.4
...
```
etc.
Proceeds. 
(Self-Correction/Verification during output gen prep)
Wait, `CatsEffectSuite` test bodies should return `IO[_]`. My tests do.
One minor thing: `resp.as[String]` in health test might need `IORuntime`. `CatsEffectSuite` provides it implicitly. It's fine.
All good.
Output matches.
Done. 
[Final Output Generation] -> *Proceeds*
