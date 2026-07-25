### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package example

import cats.effect.{IO, Ref}
import cats.implicits.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.*
import io.circe.generic.auto.*

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    freshApp.flatMap(EmberServerBuilder.default[IO].build).use(_.join)

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        for
          tasks <- store.get
          ordered = tasks.values.toList.sortBy(_.id)
        yield Ok(ordered.asJson)

      case req @ POST -> Root / "tasks" =>
        for
          task <- req.as[Task].handleErrorWith(_ => BadRequest("Missing or invalid title"))
          newId <- counter.modify(id => (id + 1, id + 1))
          newTask = Task(newId, task.title, done = false)
          _ <- store.update(_ + (newId -> newTask))
        yield Created(newTask.asJson, Location(Uri(path = s"/tasks/${newId}")))

      case GET -> Root / "tasks" / idPath =>
        for
          id <- idPath.toLongOption match
            case Some(i) => IO.pure(i)
            case None => NotFound(Json.obj("error" -> Json.fromString("Invalid id")))
          task <- store.get.map(_.get(id))
        yield task match
          case Some(t) => Ok(t.asJson)
          case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))

      case req @ PUT -> Root / "tasks" / idPath =>
        for
          id <- idPath.toLongOption match
            case Some(i) => IO.pure(i)
            case None => NotFound(Json.obj("error" -> Json.fromString("Invalid id")))
          updatedTask <- req.as[Task].handleErrorWith(_ => BadRequest("Missing or invalid task data"))
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_oldTask) =>
              val newTask = Task(id, updatedTask.title, updatedTask.done)
              _ <- store.update(_ + (id -> newTask))
              IO.pure(Ok(newTask.asJson))
            case None => IO.pure(NotFound(Json.obj("error" -> Json.fromString("Task not found"))))
        yield result

      case DELETE -> Root / "tasks" / idPath =>
        for
          id <- idPath.toLongOption match
            case Some(i) => IO.pure(i)
            case None => NotFound(Json.obj("error" -> Json.fromString("Invalid id")))
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_) =>
              _ <- store.update(_ - id)
              IO.pure(NoContent())
            case None => IO.pure(NotFound(Json.obj("error" -> Json.fromString("Task not found"))))
        yield result
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import munit.*
import org.http4s.*
import org.http4s.circe.*
import io.circe.syntax.*
import cats.effect.IO

class MainTest extends FunSuite:
  test("GET /health returns 200") {
    Main.freshApp.flatMap(_.run(Request[IO](method = Method.GET, uri = Uri(path = "/health"))))
      .map { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.asJson.map(_.toString).unsafeRunSync(), """{"status":"ok"}""")
      }
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.flatMap { app =>
      val body = """{"title":"Test task"}"""
      app.run(Request[IO](
        method = Method.POST,
        uri = Uri(path = "/tasks"),
        body = EntityEncoder[IO, String].encode(body)
      ))
    }.map { resp =>
      assertEquals(resp.status, Status.Created)
      val json = resp.asJson.unsafeRunSync()
      assertEquals((json \ "id").as[Long], Right(1L))
      assertEquals((json \ "title").as[String], Right("Test task"))
      assertEquals((json \ "done").as[Boolean], Right(false))
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](method = Method.POST, uri = Uri(path = "/tasks"), body = "{'title':'First'}".asJson.noSpaces))
        resp <- app.run(Request[IO](method = Method.GET, uri = Uri(path = "/tasks/1")))
      } yield resp
    }.map { resp =>
      assertEquals(resp.status, Status.Ok)
      val json = resp.asJson.unsafeRunSync()
      assertEquals((json \ "id").as[Long], Right(1L))
      assertEquals((json \ "title").as[String], Right("First"))
    }
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.flatMap(_.run(Request[IO](method = Method.GET, uri = Uri(path = "/tasks/999"))))
      .map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
  }

  test("DELETE existing task returns 204, subsequent GET returns 404") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](method = Method.POST, uri = Uri(path = "/tasks"), body = "{'title':'Delete me'}".asJson.noSpaces))
        deleteResp <- app.run(Request[IO](method = Method.DELETE, uri = Uri(path = "/tasks/1")))
        getResp <- app.run(Request[IO](method = Method.GET, uri = Uri(path = "/tasks/1")))
      } yield (deleteResp, getResp)
    }.map { case (deleteResp, getResp) =>
      assertEquals(deleteResp.status, Status.NoContent)
      assertEquals(getResp.status, Status.NotFound)
    }
  }
```