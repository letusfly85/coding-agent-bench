### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

import cats.effect.{IO, Ref}
import cats.syntax.all._
import io.circe.generic.auto._
import org.http4s._
import org.http4s.dsl._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

import scala.concurrent.ExecutionContext

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root => Ok(json"""{"status":"ok"}""")
    }

    val tasksRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "tasks" =>
        store.get.map { tasks =>
          val sortedTasks = tasks.values.toList.sortBy(_.id)
          Ok(sortedTasks)
        }

      case req @ POST -> Root / "tasks" =>
        for {
          title <- req.as[Map[String, String]].map(_.getOrElse("title", ""))
          id <- counter.modify(c => (c + 1, c + 1))
          task = Task(id, title, false)
          _ <- store.update(_ + (id -> task))
        } yield Created(task)

      case GET -> Root / "tasks" / idVar =>
        for {
          id <- idVar.parseLong
          task <- store.get.map(_.get(id))
          result <- task match {
            case Some(t) => Ok(t)
            case None => NotFound()
          }
        } yield result

      case req @ PUT -> Root / "tasks" / idVar =>
        for {
          id <- idVar.parseLong
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match {
            case Some(_) =>
              for {
                title <- req.as[Map[String, String]].map(_.getOrElse("title", ""))
                done <- req.as[Map[String, Boolean]].map(_.getOrElse("done", false))
                newTask = Task(id, title, done)
                _ <- store.update(_ + (id -> newTask))
              } yield Ok(newTask)
            case None => NotFound()
          }
        } yield result

      case DELETE -> Root / "tasks" / idVar =>
        for {
          id <- idVar.parseLong
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match {
            case Some(_) =>
              for {
                _ <- store.update(_ - id)
              } yield NoContent()
            case None => NotFound()
          }
        } yield result
    }

    health <+> tasksRoutes
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound
  }

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder
        .default[IO]
        .withPort(port"3000")
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

import munit.CatsEffectSuite
import org.http4s._
import org.http4s.dsl._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax._
import io.circe.parser._

import scala.util.Try

class MainTest extends CatsEffectSuite {
  private val app = Main.freshApp

  test("GET /health returns 200") {
    app.flatMap { app =>
      val req = Request[IO](Method.GET, uri"/health")
      req.run(app).map { resp =>
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        assertEquals(body, """{"status":"ok"}""")
      }
    }
  }

  test("POST /tasks returns 201 and id 1") {
    app.flatMap { app =>
      val body = """{"title":"Test task"}"""
      val req = Request[IO](Method.POST, uri"/tasks")
        .withEntity(body)
      req.run(app).map { resp =>
        assertEquals(resp.status, Status.Created)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Test task")
        assertEquals(task.done, false)
      }
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    app.flatMap { app =>
      val createReq = Request[IO](Method.POST, uri"/tasks")
        .withEntity("""{"title":"Another task"}""")
      for {
        _ <- createReq.run(app)
        getReq = Request[IO](Method.GET, uri"/tasks/1")
        resp <- getReq.run(app)
      } yield {
        assertEquals(resp.status, Status.Ok)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Another task")
        assertEquals(task.done, false)
      }
    }
  }

  test("GET /tasks/999 returns 404") {
    app.flatMap { app =>
      val req = Request[IO](Method.GET, uri"/tasks/999")
      req.run(app).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    app.flatMap { app =>
      val createReq = Request[IO](Method.POST, uri"/tasks")
        .withEntity("""{"title":"To delete"}""")
      for {
        _ <- createReq.run(app)
        deleteReq = Request[IO](Method.DELETE, uri"/tasks/1")
        deleteResp <- deleteReq.run(app)
        getReq = Request[IO](Method.GET, uri"/tasks/1")
        getResp <- getReq.run(app)
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }
  }
}
```