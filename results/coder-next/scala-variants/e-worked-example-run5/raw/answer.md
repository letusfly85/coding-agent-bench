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
          task   = Task(id, input.title, false)
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
          task  <- store.get.map(_.get(id))
          result <- task match
            case Some(oldTask) =>
              val newTask = oldTask.copy(title = input.title, done = input.done)
              store.update(_.updated(id, newTask)).map(_ => Ok(newTask))
            case None => NotFound().pure[IO]
        yield result

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
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.circe.parser._

class TaskApiTest extends FunSuite:
  test("GET /health returns 200"):
    TaskApi.freshApp.flatMap { app =>
      val req = Request[IO](Method.GET, uri"/health")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
      }
    }.unsafeRunSync()

  test("POST /tasks returns 201 and id 1"):
    TaskApi.freshApp.flatMap { app =>
      val req = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = io.circe.syntax.encode(CreateTaskReq("Test task")).orThrow
      )
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Test task")
        assertEquals(task.done, false)
      }
    }.unsafeRunSync()

  test("GET /tasks/1 after creation returns the task"):
    TaskApi.freshApp.flatMap { app =>
      for
        _ <- app.run(
          Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            body = io.circe.syntax.encode(CreateTaskReq("First task")).orThrow
          )
        )
        resp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      yield {
        assertEquals(resp.status, Status.Ok)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "First task")
        assertEquals(task.done, false)
      }
    }.unsafeRunSync()

  test("GET /tasks/999 returns 404"):
    TaskApi.freshApp.flatMap { app =>
      app.run(Request[IO](Method.GET, uri"/tasks/999")).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.unsafeRunSync()

  test("DELETE existing task returns 204 and subsequent GET returns 404"):
    TaskApi.freshApp.flatMap { app =>
      for
        _ <- app.run(
          Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            body = io.circe.syntax.encode(CreateTaskReq("To delete")).orThrow
          )
        )
        deleteResp <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
        getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }.unsafeRunSync()
```