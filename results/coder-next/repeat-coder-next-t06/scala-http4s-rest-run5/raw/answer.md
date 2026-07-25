### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package taskapi

import cats.effect.{IO, Ref}
import cats.syntax.all._
import fs2.Stream
import http4s.dsl.io._
import http4s.ember.server.EmberServerBuilder
import http4s.{HttpApp, HttpRoutes, Request, Response}
import io.circe.generic.auto._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import org.http4s.server.middleware.Logger

import java.util.concurrent.atomic.AtomicLong

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  type TaskStore = Ref[IO, Map[Long, Task]]
  type IdCounter = Ref[IO, Long]

  def routes(store: TaskStore, counter: IdCounter): HttpRoutes[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._

    HttpRoutes.of[IO] {
      case GET -> Root =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" =>
        for {
          taskRequest <- req.as[Task]
          id <- counter.updateAndGet(_ + 1)
          newTask = Task(id, taskRequest.title, false)
          _ <- store.update(_.updated(id, newTask))
          response <- Created(newTask)
        } yield response

      case GET -> Root / "tasks" / IntVar(id) =>
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for {
          taskRequest <- req.as[Task]
          tasks <- store.get
          response <- tasks.get(id) match {
            case Some(_) =>
              val updatedTask = Task(id, taskRequest.title, taskRequest.done)
              store.update(_.updated(id, updatedTask)).as(Ok(updatedTask))
            case None => NotFound().pure[IO]
          }
        } yield response

      case DELETE -> Root / "tasks" / IntVar(id) =>
        store.modify { tasks =>
          if (tasks.contains(id)) (tasks - id, Status.NoContent)
          else (tasks, Status.NotFound)
        }.flatMap(response => response.pure[IO])
    }
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](1L)
      routes = Logger.logBody(routes(store, counter))
      app = Router("/" -> routes).orNotFound
    } yield app
  }

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder.default[IO]
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_.forever)
    )
}
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4

import munit.CatsEffectSuite
import org.http4s.Method._
import org.http4s.Request
import org.http4s.Uri.Path
import org.http4s.dsl.io._
import org.http4s.implicits._

import taskapi.{Main, Task}

class MainSuite extends CatsEffectSuite {
  private val baseUri = org.http4s.Uri(path = Path.Root)

  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      val req = Request[IO](GET, baseUri / "health")
      app.run(req).map { resp =>
        assertEquals(resp.status.code, 200)
        assertEquals(resp.as[String].unsafeRunSync(), """{"status":"ok"}""")
      }
    }.flatten
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      val taskJson = """{"title":"Test task"}"""
      val req = Request[IO](
        method = POST,
        uri = baseUri / "tasks",
        body = io.circe.syntax._,
        headers = org.http4s.Headers(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))
      ).withEntity(taskJson)

      app.run(req).map { resp =>
        assertEquals(resp.status.code, 201)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Test task")
        assertEquals(task.done, false)
      }
    }.flatten
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = POST,
        uri = baseUri / "tasks"
      ).withEntity("""{"title":"Get me"}""")

      for {
        _ <- app.run(createReq).map(_.as[Task].unsafeRunSync())
        getReq = Request[IO](GET, baseUri / "tasks" / "1")
        resp <- app.run(getReq)
        task <- resp.as[Task]
      } yield {
        assertEquals(resp.status.code, 200)
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Get me")
        assertEquals(task.done, false)
      }
    }.flatten
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      val req = Request[IO](GET, baseUri / "tasks" / "999")
      app.run(req).map { resp =>
        assertEquals(resp.status.code, 404)
      }
    }.flatten
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = POST,
        uri = baseUri / "tasks"
      ).withEntity("""{"title":"To delete"}""")

      for {
        _ <- app.run(createReq).map(_.as[Task].unsafeRunSync())
        deleteReq = Request[IO](DELETE, baseUri / "tasks" / "1")
        deleteResp <- app.run(deleteReq)
        getReq = Request[IO](GET, baseUri / "tasks" / "1")
        getResp <- app.run(getReq)
      } yield {
        assertEquals(deleteResp.status.code, 204)
        assertEquals(getResp.status.code, 404)
      }
    }.flatten
  }
}
```