### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package todoapi

import cats.effect.{IO, Ref}
import cats.effect.std.Ref
import cats.syntax.all._
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import io.circe.generic.auto._

import scala.concurrent.ExecutionContext

object todoapi {
  case class Task(id: Long, title: String, done: Boolean)
  case class CreateTaskRequest(title: String)
  case class UpdateTaskRequest(title: String, done: Boolean)

  def routes(store: Ref[IO, Vector[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val dsl = Http4sDsl[IO]
    import dsl._

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(json"""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.toList))

      case req @ POST -> Root / "tasks" =>
        req.as[CreateTaskRequest].flatMap { createReq =>
          counter.modify { n =>
            val newId = n + 1
            (newId, newId)
          }.flatMap { id =>
            store.update(tasks => tasks :+ Task(id, createReq.title, false)) *>
              Created(Task(id, createReq.title, false))
          }
        }

      case GET -> Root / "tasks" / IntVar(id) =>
        store.get.map(tasks => tasks.find(_.id == id))
          .flatMap {
            case Some(task) => Ok(task)
            case None => NotFound(json"""{"error":"not found"}""")
          }

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        req.as[UpdateTaskRequest].flatMap { updateReq =>
          store.modify { tasks =>
            tasks.patch(id - 1, Seq(Task(id, updateReq.title, updateReq.done)), 1) -> 
              tasks.patch(id - 1, Seq(Task(id, updateReq.title, updateReq.done)), 1).lift(id - 1)
          }.flatMap {
            case Some(task) => Ok(task)
            case None => NotFound(json"""{"error":"not found"}""")
          }
        }

      case DELETE -> Root / "tasks" / IntVar(id) =>
        store.modify { tasks =>
          if (tasks.length >= id && tasks(id - 1).id == id) {
            val (left, right) = tasks.splitAt(id - 1)
            (left ++ right.drop(1), true)
          } else {
            (tasks, false)
          }
        }.flatMap {
          case true => NoContent()
          case false => NotFound(json"""{"error":"not found"}""")
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Vector[Task]](Vector.empty)
      counter <- Ref.of[IO, Long](0)
      router = Router("/" -> routes(store, counter)).orNotFound
      app <- EmberServerBuilder
        .default[IO]
        .withPort(port"3000")
        .withHttpApp(router)
        .build
    } yield app
  }

  object Main extends IOApp.Simple {
    def run: IO[Unit] = freshApp.flatMap(_.use(_.neverRun))
  }
}
```

### FILE: main.test.scala
```scala
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
```