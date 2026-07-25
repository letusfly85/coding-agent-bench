### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package com.example

import cats.effect.{IO, Ref}
import cats.syntax.all._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.dsl._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import io.circe.generic.auto._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  type TaskStore = Ref[IO, Map[Long, Task]]
  type IdCounter = Ref[IO, Long]

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root => Ok(json"""{"status":"ok"}""")
    }

    val tasks: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case req @ POST -> Root =>
        for {
          task <- req.as[Task]
          id <- counter.updateAndGet(_ + 1)
          newTask = Task(id, task.title, false)
          _ <- store.update(_.updated(id, newTask))
        } yield Created(newTask.asJson)

      case GET -> Root =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))

      case GET -> Root / IntVar(id) =>
        store.get.map(_.get(id))
          .flatMap {
            case Some(task) => Ok(task.asJson)
            case None => NotFound("Task not found")
          }

      case req @ PUT -> Root / IntVar(id) =>
        for {
          updatedTask <- req.as[Task]
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match {
            case Some(_) =>
              for {
                _ <- store.update(_.updated(id, updatedTask.copy(id = id)))
                updated <- store.get.map(_.get(id).get)
              } yield Ok(updated.asJson)
            case None => NotFound("Task not found")
          }
        } yield result

      case DELETE -> Root / IntVar(id) =>
        store.get.map(_.get(id))
          .flatMap {
            case Some(_) =>
              for {
                _ <- store.update(_.removed(id))
              } yield NoContent()
            case None => NotFound("Task not found")
          }
    }

    health <+> tasks
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0)
    } yield routes(store, counter).orNotFound
  }

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder.default[IO]
        .withHttpApp(app)
        .withPort(3000)
        .build
        .use(_ => IO.never)
    )
}
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package com.example

import munit.FunSuite
import cats.effect.IO
import org.http4s._
import org.http4s.dsl._
import org.http4s.circe.CirceEntityEncoder._
import io.circe.syntax._
import org.http4s.Method._
import org.http4s.Request
import org.http4s.Status

class MainTest extends FunSuite {

  test("GET /health returns 200") {
    Main.freshApp.flatMap { app =>
      val request = Request[IO](GET, uri("/health"))
      app.run(request).map { response =>
        assertEquals(response.status, Status.Ok)
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.flatMap { app =>
      val request = Request[IO](POST, uri("/tasks"))
        .withEntity("""{"title":"Test task"}""").withContentType(org.http4s.ContentType.apply(org.http4s.MediaType.application.json))
      app.run(request).map { response =>
        assertEquals(response.status, Status.Created)
        val body = response.asJson.unsafeRunSync()
        assertEquals((body \ "id").as[Long].toOption.get, 1L)
        assertEquals((body \ "title").as[String].toOption.get, "Test task")
        assertEquals((body \ "done").as[Boolean].toOption.get, false)
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](POST, uri("/tasks"))
          .withEntity("""{"title":"First task"}""").withContentType(org.http4s.ContentType.apply(org.http4s.MediaType.application.json)))
        response <- app.run(Request[IO](GET, uri("/tasks/1")))
      } yield {
        assertEquals(response.status, Status.Ok)
        val body = response.asJson.unsafeRunSync()
        assertEquals((body \ "id").as[Long].toOption.get, 1L)
        assertEquals((body \ "title").as[String].toOption.get, "First task")
        assertEquals((body \ "done").as[Boolean].toOption.get, false)
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.flatMap { app =>
      app.run(Request[IO](GET, uri("/tasks/999"))).map { response =>
        assertEquals(response.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](POST, uri("/tasks"))
          .withEntity("""{"title":"To delete"}""").withContentType(org.http4s.ContentType.apply(org.http4s.MediaType.application.json)))
        deleteResponse <- app.run(Request[IO](DELETE, uri("/tasks/1")))
        getResponse <- app.run(Request[IO](GET, uri("/tasks/1")))
      } yield {
        assertEquals(deleteResponse.status, Status.NoContent)
        assertEquals(getResponse.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }
}
```