### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package example

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import fs2.Stream
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl._
import org.http4s.server.middleware.Logger

import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root => Ok(Json.obj("status" -> Json.fromString("ok")))
    }

    val tasks: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))

      case req @ POST -> Root =>
        req.as[Task].flatMap { task =>
          counter.modify { n =>
            val newId = n + 1
            (newId, Task(newId, task.title, false))
          }.flatMap { newTask =>
            store.update(tasks => tasks + (newTask.id -> newTask)) *> Created(newTask.asJson)
          }
        }
    }

    val taskById: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case req @ GET -> Root / IntVar(id) =>
        store.get.map(_.get(id)).flatMap {
          case Some(task) => Ok(task.asJson)
          case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
        }

      case req @ PUT -> Root / IntVar(id) =>
        store.get.map(_.get(id)).flatMap {
          case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
          case Some(_) =>
            req.as[Task].flatMap { updatedTask =>
              store.modify { tasks =>
                tasks.get(id) match {
                  case Some(_) =>
                    val newTask = Task(id, updatedTask.title, updatedTask.done)
                    (tasks + (id -> newTask), newTask)
                  case None => (tasks, null.asInstanceOf[Task]) // unreachable
                }
              }.flatMap {
                case (_, null) => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
                case (_, task) => Ok(task.asJson)
              }
            }
        }

      case DELETE -> Root / IntVar(id) =>
        store.modify { tasks =>
          if (tasks.contains(id)) (tasks - id, true)
          else (tasks, false)
        }.flatMap {
          case true => NoContent()
          case false => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
        }
    }

    health <+> tasks <+> taskById
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
      routes = Logger.httpApp(routes(store, counter).orNotFound)
      app = routes
    } yield app
  }

  override def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("0.0.0.0").toOption.get)
        .withPort(Port.fromInt(3000).toOption.get)
        .withHttpApp(app)
        .build
        .use(_.join)
    )
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

package example

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.Method._
import org.http4s.Request
import org.http4s.Uri.Path
import org.http4s.syntax.all._

class MainTest extends CatsEffectSuite {

  def makeRequest(app: HttpApp[IO], method: Method, path: Path, body: Option[String] = None): IO[Response[IO]] = {
    val req = method(path) ++ body.fold(Request[IO]()) { b =>
      Request[IO]().withEntity(b)
    }
    app.run(req)
  }

  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      makeRequest(app, GET, Path.root).map { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.as[String].unsafeRunSync(), """{"status":"ok"}""")
      }
    }.flatten
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      makeRequest(app, POST, Path.root, Some("""{"title":"Test task"}""")).map { resp =>
        assertEquals(resp.status, Status.Created)
        val body = resp.as[String].unsafeRunSync()
        val json = io.circe.parser.parse(body).toOption.get
        assertEquals((json \ "id").as[Long], 1L)
        assertEquals((json \ "title").as[String], "Test task")
        assertEquals((json \ "done").as[Boolean], false)
      }
    }.flatten
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      for {
        _ <- makeRequest(app, POST, Path.root, Some("""{"title":"First task"}""")).void
        resp <- makeRequest(app, GET, Path.root / 1)
      } yield {
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        val json = io.circe.parser.parse(body).toOption.get
        assertEquals((json \ "id").as[Long], 1L)
        assertEquals((json \ "title").as[String], "First task")
        assertEquals((json \ "done").as[Boolean], false)
      }
    }.flatten
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      makeRequest(app, GET, Path.root / 999).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.flatten
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      for {
        _ <- makeRequest(app, POST, Path.root, Some("""{"title":"To delete"}""")).void
        deleteResp <- makeRequest(app, DELETE, Path.root / 1)
        getResp <- makeRequest(app, GET, Path.root / 1)
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }.flatten
  }
}
```