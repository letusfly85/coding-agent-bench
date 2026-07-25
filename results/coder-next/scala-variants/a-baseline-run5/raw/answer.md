### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.syntax.all.*
import org.http4s.*
import org.http4s.dsl.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.circe.CirceEntityCodec.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.circe.Json

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  type Store = Ref[IO, Map[Long, Task]]
  type Counter = Ref[IO, Long]

  def routes(store: Store, counter: Counter): HttpRoutes[IO] =
    val dsl = new Http4sDsl[IO] {}
    import dsl.*

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" =>
        for
          title <- req.as[String]
          id <- counter.updateAndGet(_ + 1)
          task = Task(id, title, done = false)
          _ <- store.update(_.updated(id, task))
        yield Created(task.asJson)

      case GET -> Root / "tasks" / IntVar(id) =>
        store.get.map(_.get(id)).flatMap {
          case Some(task) => Ok(task.asJson)
          case None       => NotFound()
        }

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for
          updated <- req.as[Task]
          task <- store.modify { m =>
            m.get(id) match
              case Some(_) =>
                val newMap = m.updated(id, updated.copy(id = id))
                (newMap, Some(task))
              case None => (m, None)
          }
          result <- task match
            case Some(_) => Ok(updated.asJson)
            case None    => NotFound()
        yield result

      case DELETE -> Root / "tasks" / IntVar(id) =>
        store.modify { m =>
          if m.contains(id) then (m - id, Response[IO](Status.NoContent))
          else (m, Response[IO](Status.NotFound))
        }.flatten
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

  override def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder
        .default[IO]
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.join)
    )
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
import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.syntax.*
import io.circe.parser.*
import io.circe.Json

class MainTest extends FunSuite:
  test("GET /health returns 200") {
    Main.freshApp.flatMap(app =>
      app.run(Request[IO](Method.GET, uri"/health"))
    ).map { response =>
      assertEquals(response.status, Status.Ok)
      val body = response.as[String].unsafeRunSync()
      assertEquals(parse(body), Right(Json.obj("status" -> Json.fromString("ok"))))
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.flatMap(app =>
      app.run(
        Request[IO](
          method = Method.POST,
          uri = uri"/tasks",
          body = io.circe.syntax.JsonEncoder[Json].encodeJson(Json.obj("title" -> Json.fromString("Test task")), None)
        )
      )
    ).map { response =>
      assertEquals(response.status, Status.Created)
      val body = response.as[String].unsafeRunSync()
      assertEquals(parse(body), Right(Json.obj(
        "id" -> Json.fromLong(1L),
        "title" -> Json.fromString("Test task"),
        "done" -> Json.fromBoolean(false)
      )))
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.flatMap(app =>
      for {
        _ <- app.run(
          Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            body = io.circe.syntax.JsonEncoder[Json].encodeJson(Json.obj("title" -> Json.fromString("Test task")), None)
          )
        )
        response <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      } yield response
    ).map { response =>
      assertEquals(response.status, Status.Ok)
      val body = response.as[String].unsafeRunSync()
      assertEquals(parse(body), Right(Json.obj(
        "id" -> Json.fromLong(1L),
        "title" -> Json.fromString("Test task"),
        "done" -> Json.fromBoolean(false)
      )))
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.flatMap(app =>
      app.run(Request[IO](Method.GET, uri"/tasks/999"))
    ).map { response =>
      assertEquals(response.status, Status.NotFound)
    }.unsafeRunSync()
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    Main.freshApp.flatMap(app =>
      for {
        _ <- app.run(
          Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            body = io.circe.syntax.JsonEncoder[Json].encodeJson(Json.obj("title" -> Json.fromString("Delete me")), None)
          )
        )
        deleteResp <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
        getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      } yield (deleteResp, getResp)
    ).map { case (deleteResp, getResp) =>
      assertEquals(deleteResp.status, Status.NoContent)
      assertEquals(getResp.status, Status.NotFound)
    }.unsafeRunSync()
  }
```