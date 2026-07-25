### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import org.http4s.{HttpApp, HttpRoutes}
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
            case None       => NotFound()
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
              case None       => NotFound()
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
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
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

### FILE: main.test.scala
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