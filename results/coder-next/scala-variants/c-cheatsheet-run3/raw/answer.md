### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package taskapi

import cats.effect.{IO, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._

import scala.collection.immutable

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val healthRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))
    }

    val tasksRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "tasks" =>
        store.get.map { tasks =>
          val ordered = tasks.values.toList.sortBy(_.id)
          Ok(ordered)
        }

      case req @ POST -> Root / "tasks" =>
        for {
          task <- req.as[Task]
          id <- counter.getAndUpdate(_ + 1)
          newTask = Task(id, task.title, false)
          _ <- store.update(_.updated(id, newTask))
        } yield Created(newTask)

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        for {
          task <- req.as[Task]
          updated <- store.modify { m =>
            m.get(id) match {
              case Some(_) =>
                val newTask = task.copy(id = id)
                (Ok(newTask), m.updated(id, newTask))
              case None =>
                (NotFound(), m)
            }
          }
        } yield updated

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.modify { m =>
          if (m.contains(id)) {
            (NoContent(), m - id)
          } else {
            (NotFound(), m)
          }
        }
    }

    healthRoute <+> tasksRoute
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](1L)
      routes = routes(store, counter)
    } yield routes.orNotFound
  }

  def run: IO[Unit] =
    freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.forever)
    }
}
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package taskapi

import munit.*
import cats.effect.*
import cats.effect.std.Console
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.syntax.*
import io.circe.parser.*

class TaskApiTest extends munit.CatsEffectSuite {

  private val taskApi = Main.freshApp

  test("GET /health returns 200") {
    for {
      app <- taskApi
      request = Request[IO](Method.GET, uri"/health")
      response <- app.run(request)
      body <- response.as[String]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body, """{"status":"ok"}""")
    }
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      app <- taskApi
      request = Request[IO](Method.POST, uri"/tasks")
        .withEntity("""{"title":"Test task"}""")
        .withContentType(org.http4s.ContentType(`application/json`))
      response <- app.run(request)
      body <- response.as[String]
      json <- IO.fromEither(parse(body))
    } yield {
      assertEquals(response.status, Status.Created)
      assertEquals((json \ "id").as[Long], 1L)
      assertEquals((json \ "title").as[String], "Test task")
      assertEquals((json \ "done").as[Boolean], false)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      app <- taskApi
      // First create a task
      _ <- app.run(
        Request[IO](Method.POST, uri"/tasks")
          .withEntity("""{"title":"First task"}""")
          .withContentType(org.http4s.ContentType(`application/json`))
      )
      // Then fetch it
      response <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      body <- response.as[String]
      json <- IO.fromEither(parse(body))
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals((json \ "id").as[Long], 1L)
      assertEquals((json \ "title").as[String], "First task")
      assertEquals((json \ "done").as[Boolean], false)
    }
  }

  test("GET /tasks/999 returns 404") {
    for {
      app <- taskApi
      request = Request[IO](Method.GET, uri"/tasks/999")
      response <- app.run(request)
    } yield {
      assertEquals(response.status, Status.NotFound)
    }
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    for {
      app <- taskApi
      // Create a task first
      _ <- app.run(
        Request[IO](Method.POST, uri"/tasks")
          .withEntity("""{"title":"To delete"}""")
          .withContentType(org.http4s.ContentType(`application/json`))
      )
      // Delete it
      deleteResponse <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
      // Get it again
      getResponse <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      assertEquals(getResponse.status, Status.NotFound)
    }
  }
}
```