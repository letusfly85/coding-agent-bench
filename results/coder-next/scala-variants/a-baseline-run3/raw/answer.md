### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package myapp

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import fs2.Stream
import org.http4s._
import org.http4s.dsl._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.server.Server

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  type Store = Ref[IO, Map[Long, Task]]
  type Counter = Ref[IO, Long]

  def routes(store: Store, counter: Counter): HttpRoutes[IO] =
    val service = HttpRoutes.of[IO]:
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map { tasks =>
          val sortedTasks = tasks.values.toList.sortBy(_.id)
          Ok(sortedTasks.asJson)
        }

      case req @ POST -> Root / "tasks" =>
        for
          title <- req.as[String]
          id <- counter.updateAndModify(_ + 1L)
          task = Task(id, title, done = false)
          _ <- store.update(_ + (id -> task))
        yield Created(task.asJson, Headers(Header("Location", s"/tasks/$id")))

      case GET -> Root / "tasks" / IntVar(id) =>
        store.get.map { tasks =>
          tasks.get(id) match
            case Some(task) => Ok(task.asJson)
            case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
        }

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for
          title <- req.as[String]
          _ <- store.modify { tasks =>
            tasks.get(id) match
              case Some(task) =>
                val updated = task.copy(title = title, done = task.done)
                (tasks + (id -> updated), updated)
              case None => (tasks, null: Task)
          } match
            case null => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
            case task => Ok(task.asJson)

      case DELETE -> Root / "tasks" / IntVar(id) =>
        store.modify { tasks =>
          if tasks.contains(id) then
            (tasks - id, true)
          else
            (tasks, false)
        }.flatMap:
          case true => NoContent()
          case false => NotFound(Json.obj("error" -> Json.fromString("Task not found")))

    service

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder.default[IO]
        .withHttpApp(app)
        .withPort(3000)
        .build
        .use(_ => IO.never)
    )
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package myapp

import munit.*
import cats.effect.IO
import org.http4s.*
import org.http4s.dsl._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import io.circe.parser._
import io.circe.syntax._

class MainTest extends FunSuite:

  test("GET /health returns 200") {
    Main.freshApp.flatMap(app =>
      Request[IO](Method.GET, uri("/health")).run(app).map { response =>
        assertEquals(response.status, Status.Ok)
        val body = response.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertEquals(json.hcursor.downField("status").as[String], Right("ok"))
      }
    ).unsafeRunSync()
  }

  test("POST /tasks returns 201 with id 1") {
    Main.freshApp.flatMap(app =>
      Request[IO](Method.POST, uri("/tasks"))
        .withEntity("""{"title":"Test task"}""")
        .run(app).map { response =>
          assertEquals(response.status, Status.Created)
          val body = response.as[String].unsafeRunSync()
          val json = parse(body).getOrElse(Json.Null)
          assertEquals(json.hcursor.downField("id").as[Long], Right(1L))
          assertEquals(json.hcursor.downField("title").as[String], Right("Test task"))
          assertEquals(json.hcursor.downField("done").as[Boolean], Right(false))
        }
    ).unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.flatMap(app =>
      for {
        _ <- Request[IO](Method.POST, uri("/tasks"))
          .withEntity("""{"title":"First task"}""")
          .run(app)
        response <- Request[IO](Method.GET, uri("/tasks/1")).run(app)
      } yield {
        assertEquals(response.status, Status.Ok)
        val body = response.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertEquals(json.hcursor.downField("id").as[Long], Right(1L))
        assertEquals(json.hcursor.downField("title").as[String], Right("First task"))
        assertEquals(json.hcursor.downField("done").as[Boolean], Right(false))
      }
    ).unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.flatMap(app =>
      Request[IO](Method.GET, uri("/tasks/999")).run(app).map { response =>
        assertEquals(response.status, Status.NotFound)
        val body = response.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertTrue(json.hcursor.downField("error").as[String].isRight)
      }
    ).unsafeRunSync()
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.flatMap(app =>
      for {
        _ <- Request[IO](Method.POST, uri("/tasks"))
          .withEntity("""{"title":"To delete"}""")
          .run(app)
        deleteResponse <- Request[IO](Method.DELETE, uri("/tasks/1")).run(app)
        getResponse <- Request[IO](Method.GET, uri("/tasks/1")).run(app)
      } yield {
        assertEquals(deleteResponse.status, Status.NoContent)
        assertEquals(getResponse.status, Status.NotFound)
      }
    ).unsafeRunSync()
  }
```