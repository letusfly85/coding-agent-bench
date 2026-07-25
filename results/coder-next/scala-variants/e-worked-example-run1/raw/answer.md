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
          task   = Task(id, input.title, done = false)
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
            case Some(_) =>
              store.modify { m =>
                val newTask = Task(id, input.title, input.done)
                (m.updated(id, newTask), newTask)
              }
            case None => IO.pure(Option.empty[Task])
          resp <- result match
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
//> using test.dep org.scalameta::munit::1.0.4

import munit.*
import cats.effect.{IO, Ref}
import org.http4s.*
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec.*
import io.circe.syntax.*
import io.circe.parser._
import org.http4s.Method.*
import org.http4s.Request
import cats.effect.unsafe.implicits.global

class TaskApiTest extends FunSuite:

  test("GET /health returns 200") {
    for
      app <- TaskApi.freshApp
      request = Request[IO](method = GET, uri = uri"/health")
      response <- app.run(request)
    yield assertEquals(response.status, Status.Ok)
  }

  test("POST /tasks returns 201 and id 1") {
    for
      app <- TaskApi.freshApp
      request = Request[IO](
        method = POST,
        uri = uri"/tasks",
        body = """{"title":"First task"}""".asJson.noSpaces
      )
      response <- app.run(request)
      body <- response.as[String]
      json <- IO.fromEither(parse(body))
      id = json("id").as[Long].toOption.get
    yield {
      assertEquals(response.status, Status.Created)
      assertEquals(id, 1L)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](method = POST, uri = uri"/tasks", body = """{"title":"Task 1"}""".asJson.noSpaces))
      response <- app.run(Request[IO](method = GET, uri = uri"/tasks/1"))
      body <- response.as[String]
      json <- IO.fromEither(parse(body))
      title = json("title").as[String].toOption.get
    yield assertEquals(title, "Task 1")
  }

  test("GET /tasks/999 returns 404") {
    for
      app <- TaskApi.freshApp
      request = Request[IO](method = GET, uri = uri"/tasks/999")
      response <- app.run(request)
    yield assertEquals(response.status, Status.NotFound)
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    for
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](method = POST, uri = uri"/tasks", body = """{"title":"Task to delete"}""".asJson.noSpaces))
      deleteResponse <- app.run(Request[IO](method = DELETE, uri = uri"/tasks/1"))
      getResponse <- app.run(Request[IO](method = GET, uri = uri"/tasks/1"))
    yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      assertEquals(getResponse.status, Status.NotFound)
    }
  }
```