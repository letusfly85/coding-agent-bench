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

case class Task(id: Long, title: String, done: Boolean)
case class CreateTaskReq(title: String)
case class UpdateTaskReq(title: String, done: Boolean)

object Api:
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" => 
        Ok(json"""{"status":"ok"}""")
      
      case GET -> Root / "tasks" => 
        store.get.map { tasks =>
          val sortedTasks = tasks.values.toList.sortBy(_.id)
          Ok(sortedTasks)
        }
      
      case req @ POST -> Root / "tasks" => 
        for {
          body <- req.as[CreateTaskReq]
          newId <- counter.updateAndGet(_ + 1)
          task = Task(newId, body.title, done = false)
          _ <- store.update(_ + (newId -> task))
          response <- Created(task)
        } yield response
      
      case GET -> Root / "tasks" / LongVar(id) => 
        store.get.map { tasks =>
          tasks.get(id) match
            case Some(task) => Ok(task)
            case None => NotFound()
        }
      
      case req @ PUT -> Root / "tasks" / LongVar(id) => 
        for {
          body <- req.as[UpdateTaskReq]
          result <- store.modify { tasks =>
            tasks.get(id) match
              case Some(_) =>
                val updatedTask = Task(id, body.title, body.done)
                (tasks + (id -> updatedTask), Some(updatedTask))
              case None => (tasks, None)
          }
          response <- result match
            case Some(task) => Ok(task)
            case None => NotFound()
        } yield response
      
      case DELETE -> Root / "tasks" / LongVar(id) => 
        result <- store.modify { tasks =>
          tasks.get(id) match
            case Some(_) => (tasks - id, true)
            case None => (tasks, false)
        }
        response <- if result then NoContent() else NotFound()
      } yield response
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

object Main extends IOApp.Simple:
  val run: IO[Unit] =
    Api.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .useForever
    }
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using test.dep org.scalameta::munit::1.0.4

import munit.*
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.generic.auto._
import io.circe.syntax._

class TaskApiSpec extends FunSuite:
  test("GET /health returns 200") {
    Api.freshApp
      .flatMap(_.run(Request[IO](Method.GET, uri"/health")))
      .map { response =>
        assertEquals(response.status, Status.Ok)
        assertEquals(response.body.compile.string.unsafeRunSync(), """{"status":"ok"}""")
      }
      .unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Api.freshApp
      .flatMap { app =>
        val req = Request[IO](Method.POST, uri"/tasks")
          .withEntity(CreateTaskReq("Test task"))
        app.run(req).map { response =>
          assertEquals(response.status, Status.Created)
          response.as[Task].map { task =>
            assertEquals(task.id, 1L)
            assertEquals(task.title, "Test task")
            assertEquals(task.done, false)
          }
        }
      }
      .unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Api.freshApp
      .flatMap { app =>
        for {
          _ <- app.run(Request[IO](Method.POST, uri"/tasks").withEntity(CreateTaskReq("First task")))
          response <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
        } yield response
      }
      .map { response =>
        assertEquals(response.status, Status.Ok)
        response.as[Task].map { task =>
          assertEquals(task.id, 1L)
          assertEquals(task.title, "First task")
          assertEquals(task.done, false)
        }
      }
      .unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Api.freshApp
      .flatMap(_.run(Request[IO](Method.GET, uri"/tasks/999")))
      .map { response =>
        assertEquals(response.status, Status.NotFound)
      }
      .unsafeRunSync()
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    Api.freshApp
      .flatMap { app =>
        for {
          _ <- app.run(Request[IO](Method.POST, uri"/tasks").withEntity(CreateTaskReq("To delete")))
          deleteResponse <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
          getResponse <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
        } yield (deleteResponse, getResponse)
      }
      .map { case (deleteResponse, getResponse) =>
        assertEquals(deleteResponse.status, Status.NoContent)
        assertEquals(getResponse.status, Status.NotFound)
      }
      .unsafeRunSync()
  }
```