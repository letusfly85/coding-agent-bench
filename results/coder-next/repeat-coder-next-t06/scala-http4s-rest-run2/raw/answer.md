### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package myapp

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.*
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto.*
import io.circe.syntax.*

import scala.concurrent.ExecutionContext

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  type Store = Ref[IO, Map[Long, Task]]
  type Counter = Ref[IO, Long]

  def routes(store: Store, counter: Counter): HttpRoutes[IO] =
    val dsl = new Http4sDsl[IO]{}
    import dsl.*

    HttpRoutes.of[IO] {
      case GET -> Root =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" =>
        for
          title <- req.as[String]
          id <- counter.updateAndGet(_ + 1)
          task = Task(id, title, done = false)
          _ <- store.update(_ + (id -> task))
        yield Created(task.asJson)

      case GET -> Root / "tasks" / IntVar(id) =>
        store.get.map(_.get(id))
          .flatMap {
            case Some(task) => Ok(task.asJson)
            case None => NotFound()
          }

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for
          updated <- req.as[Task]
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_) =>
              store.update(tasks => tasks + (id -> updated))
              Ok(updated.asJson)
            case None => NotFound()
        yield result

      case DELETE -> Root / "tasks" / IntVar(id) =>
        for
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_) =>
              store.update(tasks => tasks - id)
              NoContent()
            case None => NotFound()
        yield result
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder
        .default[IO]
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_.compile.drain)
    )
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package myapp

import munit.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import io.circe.syntax.*
import io.circe.generic.auto.*

class MainTest extends AsyncSuite:
  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      client
        .run(Request(GET, uri"/health"))
        .map { resp =>
          assertEquals(resp.status, Status.Ok)
          assertEquals(resp.as[String].unsafeRunSync(), """{"status":"ok"}""")
        }
    }.futureValue
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      client
        .run(Request(POST, uri"/tasks").withEntity("""{"title":"Test task"}"""))
        .map { resp =>
          assertEquals(resp.status, Status.Created)
          val task = resp.as[Task].unsafeRunSync()
          assertEquals(task.id, 1L)
          assertEquals(task.title, "Test task")
          assertEquals(task.done, false)
        }
    }.futureValue
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      for {
        _ <- client
          .run(Request(POST, uri"/tasks").withEntity("""{"title":"First task"}"""))
          .map(_.discard)
        resp <- client.run(Request(GET, uri"/tasks/1"))
      } yield {
        assertEquals(resp.status, Status.Ok)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "First task")
        assertEquals(task.done, false)
      }
    }.futureValue
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      client
        .run(Request(GET, uri"/tasks/999"))
        .map { resp =>
          assertEquals(resp.status, Status.NotFound)
        }
    }.futureValue
  }

  test("DELETE an existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      for {
        _ <- client
          .run(Request(POST, uri"/tasks").withEntity("""{"title":"To delete"}"""))
          .map(_.discard)
        deleteResp <- client.run(Request(DELETE, uri"/tasks/1"))
        getResp <- client.run(Request(GET, uri"/tasks/1"))
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }.futureValue
  }
```