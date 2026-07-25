### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
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

object TaskApi:
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {

      case GET -> Root / "health" =>
        Ok(Map("status" -> "ok"))

      case GET -> Root / "tasks" =>
        store.get.flatMap(m => Ok(m.values.toList.sortBy(_.id)))

      case req @ POST -> Root / "tasks" =>
        for
          input <- req.as[CreateTaskReq]
          id    <- counter.updateAndGet(_ + 1)
          task  <- IO(Task(id, input.title, false))
          _     <- store.update(_.updated(id, task))
          resp  <- Created(task)
        yield resp

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { m =>
          m.get(id) match
            case Some(task) => Ok(task)
            case None       => NotFound()
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        for
          input <- req.as[UpdateTaskReq]
          task  <- store.get.map(_.get(id)).flatMap {
            case Some(_) =>
              IO(Task(id, input.title, input.done)).flatMap { updatedTask =>
                store.update(_.updated(id, updatedTask)).map(_ => updatedTask)
              }
            case None => IO.pure(None)
          }
          resp <- task match
            case Some(updatedTask) => Ok(updatedTask)
            case None              => NotFound()
        yield resp

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.modify(m => (m - id, m.contains(id))).flatMap { existed =>
          if existed then NoContent() else NotFound()
        }
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

object Main extends IOApp.Simple:
  val run: IO[Unit] =
    TaskApi.freshApp.flatMap { app =>
      EmberServerBuilder.default[IO]
        .withHost(host"0.0.0.0").withPort(port"3000")
        .withHttpApp(app).build.useForever
    }
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using dep org.scalameta::munit::1.0.4

import munit.*
import cats.effect.{IO, Ref}
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax._
import io.circe.generic.auto._
import cats.effect.unsafe.implicits.global

class TaskApiTest extends FunSuite:

  test("GET /health returns 200") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val request = Request[IO](Method.GET, uri"/health")
    request.run(app).map { response =>
      assertEquals(response.status, Status.Ok)
    }
  }

  test("POST /tasks returns 201 and id 1") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val task = """{"title":"first task"}"""
    val request = Request[IO](Method.POST, uri"/tasks")
      .withBody(task)
    request.run(app).map { response =>
      assertEquals(response.status, Status.Created)
      val taskResponse = response.as[Task].unsafeRunSync()
      assertEquals(taskResponse.id, 1L)
      assertEquals(taskResponse.title, "first task")
      assertFalse(taskResponse.done)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    val app = TaskApi.freshApp.unsafeRunSync()
    
    // First create a task
    val createRequest = Request[IO](Method.POST, uri"/tasks")
      .withBody("""{"title":"test task"}""")
    createRequest.run(app).unsafeRunSync()
    
    // Then get it
    val getRequest = Request[IO](Method.GET, uri"/tasks/1")
    getRequest.run(app).map { response =>
      assertEquals(response.status, Status.Ok)
      val task = response.as[Task].unsafeRunSync()
      assertEquals(task.id, 1L)
      assertEquals(task.title, "test task")
    }
  }

  test("GET /tasks/999 returns 404") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val request = Request[IO](Method.GET, uri"/tasks/999")
    request.run(app).map { response =>
      assertEquals(response.status, Status.NotFound)
    }
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    val app = TaskApi.freshApp.unsafeRunSync()
    
    // First create a task
    val createRequest = Request[IO](Method.POST, uri"/tasks")
      .withBody("""{"title":"to delete"}""")
    createRequest.run(app).unsafeRunSync()
    
    // Delete the task
    val deleteRequest = Request[IO](Method.DELETE, uri"/tasks/1")
    deleteRequest.run(app).map { response =>
      assertEquals(response.status, Status.NoContent)
    }
    
    // Verify deletion
    val getRequest = Request[IO](Method.GET, uri"/tasks/1")
    getRequest.run(app).map { response =>
      assertEquals(response.status, Status.NotFound)
    }
  }
```