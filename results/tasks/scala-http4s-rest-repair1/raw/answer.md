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
import org.http4s.circe._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import io.circe.syntax._
import com.comcast.ip4s._

case class Task(id: Long, title: String, done: Boolean)
case class CreateTaskReq(title: String)
case class UpdateTaskReq(title: String, done: Boolean)

object Api {
  def routes(
      store: Ref[IO, Map[Long, Task]],
      counter: Ref[IO, Long]
  ): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok("""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id).asJson)

      case req @ POST -> Root / "tasks" =>
        req.as[CreateTaskReq].flatMap { input =>
          counter.modify(c => (c + 1, c + 1)).flatMap { id =>
            val task = Task(id, input.title, done = false)
            store.update(_.updated(id, task))
            Created(task)
          }
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { map =>
          map.get(id) match {
            case Some(task) => Ok(task)
            case None       => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[UpdateTaskReq].flatMap { input =>
          store.updateAndGet { map =>
            map.get(id) match {
              case Some(_) => map.updated(id, Task(id, input.title, input.done))
              case None    => map
            }
          }.flatMap { map =>
            if (map.contains(id)) Ok(map(id))
            else NotFound()
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(m => m - id).flatMap { map =>
          if (map.contains(id)) NoContent()
          else NotFound()
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound
  }
}

object Main extends IOApp.Simple {
  val run: IO[Unit] = Api.freshApp.flatMap { app =>
    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(3000)
      .withHttpApp(app)
      .build
      .useForever
  }
}
```

### FILE: main.test.scala
```scala
import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.implicits._
import org.http4s.circe._
import io.circe.parser.decode
import io.circe.generic.auto._

class TaskApiSuite extends CatsEffectSuite {

  val app: IO[HttpApp[IO]] = Api.freshApp

  test("GET /health returns 200") {
    for {
      a <- app
      req = Request[IO](method = Method.GET, uri = uri"/health")
      resp <- a(req)
    } yield {
      assertEquals(resp.status, Status.Ok)
    }
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      a <- app
      req = Request[IO](method = Method.POST, uri = uri"/tasks")
        .withEntity("""{"title":"First task"}""", MediaType.application.json)
      resp <- a(req)
      body <- resp.as[String]
      task = decode[Task](body).valueOr(e => throw new RuntimeException(e))
    } yield {
      assertEquals(resp.status, Status.Created)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "First task")
      assertEquals(task.done, false)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      a <- app
      createReq = Request[IO](method = Method.POST, uri = uri"/tasks")
        .withEntity("""{"title":"Test task"}""", MediaType.application.json)
      createResp <- a(createReq)
      _ <- createResp.body.drain
      getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
      getResp <- a(getReq)
      body <- getResp.as[String]
      task = decode[Task](body).valueOr(e => throw new RuntimeException(e))
    } yield {
      assertEquals(getResp.status, Status.Ok)
      assertEquals(task.title, "Test task")
    }
  }

  test("GET /tasks/999 returns 404") {
    for {
      a <- app
      req = Request[IO](method = Method.GET, uri = uri"/tasks/999")
      resp <- a(req)
    } yield {
      assertEquals(resp.status, Status.NotFound)
    }
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    for {
      a <- app
      createReq = Request[IO](method = Method.POST, uri = uri"/tasks")
        .withEntity("""{"title":"To delete"}""", MediaType.application.json)
      createResp <- a(createReq)
      _ <- createResp.body.drain
      deleteReq = Request[IO](method = Method.DELETE, uri = uri"/tasks/1")
      deleteResp <- a(deleteReq)
      getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
      getResp <- a(getReq)
    } yield {
      assertEquals(deleteResp.status, Status.NoContent)
      assertEquals(getResp.status, Status.NotFound)
    }
  }
}
```