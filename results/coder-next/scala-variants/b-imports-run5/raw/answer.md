### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
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
import io.circe.syntax._
import io.circe.parser._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  import Task._

  def routes(store: Ref[IO, List[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root => Ok(json"""{"status":"ok"}""")
    }

    val tasks: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case req @ GET -> Root =>
        store.get.map(tasks => Ok(tasks.sortBy(_.id).asJson))

      case req @ POST -> Root =>
        for {
          task <- req.as[Task]
          id <- counter.updateAndGet(_ + 1)
          newTask = Task(id, task.title, false)
          _ <- store.update(newTask :: _)
        } yield Created(newTask.asJson)

      case GET -> Root / IntVar(id) =>
        store.get.map(_.find(_.id == id))
          .flatMap {
            case Some(task) => Ok(task.asJson)
            case None => NotFound()
          }

      case req @ PUT -> Root / IntVar(id) =>
        for {
          updated <- req.as[Task]
          tasks <- store.get
          result <- tasks.find(_.id == id) match {
            case Some(task) =>
              val newTask = task.copy(title = updated.title, done = updated.done)
              store.update(newTask :: tasks.filterNot(_.id == id)) *> Ok(newTask.asJson)
            case None => NotFound()
          }
        } yield result

      case DELETE -> Root / IntVar(id) =>
        store.modify { tasks =>
          val (found, remaining) = tasks.partition(_.id == id)
          (remaining, found.nonEmpty)
        }.flatMap {
          case true => NoContent()
          case false => NotFound()
        }
    }

    health <+> tasks
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, List[Task]](List.empty)
      counter <- Ref.of[IO, Long](0)
      routesApp = routes(store, counter).orNotFound
      app = HttpApp[IO](routesApp)
    } yield app
  }

  def run: IO[Unit] =
    freshApp.flatMap(app => EmberServerBuilder[IO]
      .withHost(ip"0.0.0.0")
      .withPort(port"3000")
      .withHttpApp(app)
      .build
      .use(_.forever)
    )
}
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import munit._
import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax._
import io.circe.parser._
import io.circe.generic.auto._

class MainTest extends FunSuite {
  import Main.freshApp

  test("GET /health returns 200") {
    freshApp.flatMap(app => app.run(Request[IO](Method.GET, uri"/health")))
      .map(response => assertEquals(response.status, Status.Ok))
  }

  test("POST /tasks returns 201 and id 1") {
    freshApp.flatMap { app =>
      val request = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = io.circe.syntax.encode(Task(0L, "Test task", false)).orThrow.asJson.noSpaces
      )
      app.run(request)
    }.map { response =>
      assertEquals(response.status, Status.Created)
      val body = response.as[String].unsafeRunSync()
      val json = parse(body).getOrElse(io.circe.Json.Null)
      assertEquals((json \ "id").as[Long].toOption.get, 1L)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](Method.POST, uri"/tasks", body = """{"title":"First task"}"""))
        response <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      } yield response
    }.map { response =>
      assertEquals(response.status, Status.Ok)
      val body = response.as[String].unsafeRunSync()
      val json = parse(body).getOrElse(io.circe.Json.Null)
      assertEquals((json \ "id").as[Long].toOption.get, 1L)
      assertEquals((json \ "title").as[String].toOption.get, "First task")
      assertEquals((json \ "done").as[Boolean].toOption.get, false)
    }
  }

  test("GET /tasks/999 returns 404") {
    freshApp.flatMap(app => app.run(Request[IO](Method.GET, uri"/tasks/999")))
      .map(response => assertEquals(response.status, Status.NotFound))
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](Method.POST, uri"/tasks", body = """{"title":"To delete"}"""))
        deleteResp <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
        getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      } yield (deleteResp, getResp)
    }.map { case (deleteResp, getResp) =>
      assertEquals(deleteResp.status, Status.NoContent)
      assertEquals(getResp.status, Status.NotFound)
    }
  }
}
```