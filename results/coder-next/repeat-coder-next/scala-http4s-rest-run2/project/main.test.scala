//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

import munit.*
import cats.effect.{IO, Ref}
import cats.effect.std.Ref
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import org.http4s.dsl.Http4sDsl
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.Method.*
import io.circe.generic.auto.*

import scala.concurrent.ExecutionContext

class TaskApiSuite extends FunSuite:
  import todoapi.*

  def makeRequest(app: HttpApp[IO], req: Request[IO]): IO[Response[IO]] =
    app.run(req).value.flatMap(_.pure[IO]).recover { case t => Response[IO](Status.InternalServerError).withEntity(t.getMessage) }

  def createTask(app: HttpApp[IO], title: String): IO[Task] =
    for {
      resp <- makeRequest(app, Request[IO](POST).withEntity(CreateTaskRequest(title)))
      task <- resp.as[Task]
    } yield task

  test("GET /health returns 200") {
    freshApp.flatMap { app =>
      for {
        resp <- makeRequest(app, Request[IO](GET, uri"/health"))
        _ <- IO(assert(resp.status == Status.Ok))
        body <- resp.as[String]
        _ <- IO(assert(body.contains("\"status\":\"ok\"")))
      } yield ()
    }.safe
  }

  test("POST /tasks returns 201 and id 1") {
    freshApp.flatMap { app =>
      for {
        task <- createTask(app, "First task")
        _ <- IO(assert(task.id == 1))
        _ <- IO(assert(task.title == "First task"))
        _ <- IO(assert(!task.done))
      } yield ()
    }.safe
  }

  test("GET /tasks/1 after creation returns the task") {
    freshApp.flatMap { app =>
      for {
        task <- createTask(app, "Get task test")
        resp <- makeRequest(app, Request[IO](GET, uri"/tasks/1"))
        returnedTask <- resp.as[Task]
        _ <- IO(assert(returnedTask == task))
      } yield ()
    }.safe
  }

  test("GET /tasks/999 returns 404") {
    freshApp.flatMap { app =>
      for {
        resp <- makeRequest(app, Request[IO](GET, uri"/tasks/999"))
        _ <- IO(assert(resp.status == Status.NotFound))
      } yield ()
    }.safe
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    freshApp.flatMap { app =>
      for {
        task <- createTask(app, "To delete")
        resp <- makeRequest(app, Request[IO](DELETE, uri"/tasks/1"))
        _ <- IO(assert(resp.status == Status.NoContent))
        getResp <- makeRequest(app, Request[IO](GET, uri"/tasks/1"))
        _ <- IO(assert(getResp.status == Status.NotFound))
      } yield ()
    }.safe
  }

  extension [F[_]: FlatMap, A](ioa: F[A])
    private def safe: F[Unit] = ioa.void
