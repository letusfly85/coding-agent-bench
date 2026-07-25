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
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  type TaskStore = Map[Long, Task]

  def routes(store: Ref[IO, TaskStore], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))
    }

    val tasks: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "tasks" =>
        for {
          tasks <- store.get
          sortedTasks = tasks.values.toList.sortBy(_.id)
        } yield Ok(sortedTasks.asJson)

      case req @ POST -> Root / "tasks" =>
        for {
          task <- req.as[Task]
          id <- counter.updateAndGet(_ + 1)
          newTask = Task(id, task.title, false)
          _ <- store.update(_.updated(id, newTask))
        } yield Created(newTask.asJson)

      case GET -> Root / "tasks" / LongVar(id) =>
        for {
          tasks <- store.get
          task <- tasks.get(id) match {
            case Some(t) => Ok(t.asJson)
            case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
          }
        } yield task

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        for {
          updatedTask <- req.as[Task]
          tasks <- store.get
          result <- if (tasks.contains(id)) {
            val newTask = Task(id, updatedTask.title, updatedTask.done)
            store.update(_.updated(id, newTask)).as(Ok(newTask.asJson))
          } else {
            NotFound(Json.obj("error" -> Json.fromString("Task not found"))).pure[IO]
          }
        } yield result

      case DELETE -> Root / "tasks" / LongVar(id) =>
        for {
          (deleted, exists) <- store.modify(m => (m - id, m.contains(id)))
          result <- if (exists) {
            NoContent().pure[IO]
          } else {
            NotFound(Json.obj("error" -> Json.fromString("Task not found"))).pure[IO]
          }
        } yield result
    }

    health <+> tasks
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, TaskStore](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound
  }

  def run: IO[Unit] =
    freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.joined.as(println("Server started on port 3000")))
    }
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

import munit._
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax._
import io.circe.parser._
import org.http4s.Method._
import org.http4s.Request
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType

class MainTest extends FunSuite {
  import Main._

  def runRequest(app: HttpApp[IO], req: Request[IO]): IO[Response[IO]] =
    app.run(req)

  test("GET /health returns 200") {
    freshApp.flatMap { app =>
      val req = Request[IO](GET, uri"/health")
      runRequest(app, req).map { resp =>
        assertEquals(resp.status, Status.Ok)
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    freshApp.flatMap { app =>
      val body = """{"title":"Test task"}"""
      val req = Request[IO](
        POST,
        uri"/tasks",
        headers = Headers.of(`Content-Type`(MediaType.ApplicationJson)),
        body = body
      )
      runRequest(app, req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val json = parse(resp.bodyText.compileAsString.unsafeRunSync()).toOption.get
        assertEquals((json \ "id").as[Long].toOption.get, 1L)
        assertEquals((json \ "title").as[String].toOption.get, "Test task")
        assertEquals((json \ "done").as[Boolean].toOption.get, false)
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    freshApp.flatMap { app =>
      val createBody = """{"title":"Task to get"}"""
      val createReq = Request[IO](
        POST,
        uri"/tasks",
        headers = Headers.of(`Content-Type`(MediaType.ApplicationJson)),
        body = createBody
      )
      for {
        createResp <- runRequest(app, createReq)
        getReq = Request[IO](GET, uri"/tasks/1")
        getResp <- runRequest(app, getReq)
      } yield {
        assertEquals(getResp.status, Status.Ok)
        val json = parse(getResp.bodyText.compileAsString.unsafeRunSync()).toOption.get
        assertEquals((json \ "id").as[Long].toOption.get, 1L)
        assertEquals((json \ "title").as[String].toOption.get, "Task to get")
        assertEquals((json \ "done").as[Boolean].toOption.get, false)
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    freshApp.flatMap { app =>
      val req = Request[IO](GET, uri"/tasks/999")
      runRequest(app, req).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    freshApp.flatMap { app =>
      val createBody = """{"title":"Task to delete"}"""
      val createReq = Request[IO](
        POST,
        uri"/tasks",
        headers = Headers.of(`Content-Type`(MediaType.ApplicationJson)),
        body = createBody
      )
      for {
        _ <- runRequest(app, createReq)
        deleteReq = Request[IO](DELETE, uri"/tasks/1")
        deleteResp <- runRequest(app, deleteReq)
        getReq = Request[IO](GET, uri"/tasks/1")
        getResp <- runRequest(app, getReq)
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }
}
```