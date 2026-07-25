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

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  def routes(store: Ref[IO, Vector[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root => Ok(json"""{"status":"ok"}""")
    }

    val tasks: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root =>
        store.get.map(tasks => Ok(tasks.toList.asJson))

      case req @ POST -> Root =>
        req.as[Task].flatMap { task =>
          counter.modify { c =>
            val newId = c + 1
            (newId, Task(newId, task.title, false))
          }.flatMap { newTask =>
            store.update(tasks => tasks :+ newTask).as(Created(newTask.asJson))
          }
        }
    }

    val taskById: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case req @ GET -> Root / IntVar(id) =>
        store.get.map(_.find(_.id == id))
          .flatMap {
            case Some(task) => Ok(task.asJson)
            case None => NotFound(json"""{"error":"Task not found"}""")
          }

      case req @ PUT -> Root / IntVar(id) =>
        store.get.map(_.find(_.id == id))
          .flatMap {
            case Some(_) =>
              req.as[Task].flatMap { updatedTask =>
                store.update(tasks => tasks.map { t =>
                  if (t.id == id) Task(id, updatedTask.title, updatedTask.done)
                  else t
                }).as(Ok(updatedTask.asJson))
              }
            case None => NotFound(json"""{"error":"Task not found"}""")
          }

      case DELETE -> Root / IntVar(id) =>
        store.modify { tasks =>
          val (before, rest) = tasks.partition(_.id != id)
          (rest, if (rest.size < tasks.size) () else ())
        }.flatMap {
          case () => NoContent()
          case _ => NotFound(json"""{"error":"Task not found"}""")
        }
    }

    health <+> tasks <+> taskById
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Vector[Task]](Vector.empty)
      counter <- Ref.of[IO, Long](0L)
      routes = routes(store, counter)
    } yield routes.orNotFound
  }

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder
        .default[IO]
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
import cats.effect.{IO, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import com.comcast.ip4s._

class MainTest extends FunSuite {
  private val task1 = Task(1L, "Test task", false)
  private val task2 = Task(2L, "Another task", true)

  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      val request = Request[IO](method = Method.GET, uri = uri"/health")
      app.run(request).map { response =>
        assertEquals(response.status, Status.Ok)
        val body = response.as[String].unsafeRunSync()
        assertEquals(body, """{"status":"ok"}""")
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      val request = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = task1.copy(id = 0L).asJson.noSpaces
      )
      app.run(request).map { response =>
        assertEquals(response.status, Status.Created)
        val body = response.as[String].unsafeRunSync()
        val parsed = parse(body).toOption.getOrElse(throw new RuntimeException("Invalid JSON"))
        assertEquals(parsed.hcursor.downField("id").as[Long].toOption, Some(1L))
        assertEquals(parsed.hcursor.downField("title").as[String].toOption, Some("Test task"))
        assertEquals(parsed.hcursor.downField("done").as[Boolean].toOption, Some(false))
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = task1.copy(id = 0L).asJson.noSpaces
      )
      app.run(createReq).flatMap { _ =>
        val getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
        app.run(getReq).map { response =>
          assertEquals(response.status, Status.Ok)
          val body = response.as[String].unsafeRunSync()
          val parsed = parse(body).toOption.getOrElse(throw new RuntimeException("Invalid JSON"))
          assertEquals(parsed.hcursor.downField("id").as[Long].toOption, Some(1L))
          assertEquals(parsed.hcursor.downField("title").as[String].toOption, Some("Test task"))
          assertEquals(parsed.hcursor.downField("done").as[Boolean].toOption, Some(false))
        }
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      val request = Request[IO](method = Method.GET, uri = uri"/tasks/999")
      app.run(request).map { response =>
        assertEquals(response.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = task1.copy(id = 0L).asJson.noSpaces
      )
      app.run(createReq).flatMap { _ =>
        val deleteReq = Request[IO](method = Method.DELETE, uri = uri"/tasks/1")
        app.run(deleteReq).flatMap { deleteResponse =>
          assertEquals(deleteResponse.status, Status.NoContent)
          val getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
          app.run(getReq).map { getResponse =>
            assertEquals(getResponse.status, Status.NotFound)
          }
        }
      }
    }.unsafeRunSync()
  }
}
```