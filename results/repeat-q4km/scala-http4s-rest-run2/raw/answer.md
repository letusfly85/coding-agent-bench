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
import org.http4s.ember.server.EmberServerBuilder
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)
case class CreateTask(title: String)
case class UpdateTask(title: String, done: Boolean)

object App {
  type TaskStore = Map[Long, Task]

  def routes(store: Ref[IO, TaskStore], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health = GET(path("health")) {
      Ok(Json.obj("status" -> Json.fromString("ok")))
    }

    val getAll = GET(path("tasks")) {
      store.get.map { tasks =>
        Ok(tasks.values.toList.sortBy(_.id).asJson)
      }
    }

    val create = POST(path("tasks")) {
      as[CreateTask].flatMap { input =>
        counter.updateAndGet(_ + 1).flatMap { id =>
          val task = Task(id, input.title, done = false)
          store.update(_.updated(id, task)).as(Created(task))
        }
      }
    }

    val getById = GET(path("tasks" / Long)) { id =>
      store.get.flatMap { tasks =>
        tasks.get(id) match {
          case Some(task) => Ok(task)
          case None => NotFound()
        }
      }
    }

    val update = PUT(path("tasks" / Long)) { id =>
      as[UpdateTask].flatMap { input =>
        store.get.flatMap { tasks =>
          tasks.get(id) match {
            case Some(existing) =>
              val updated = existing.copy(title = input.title, done = input.done)
              store.update(_.updated(id, updated)).as(Ok(updated))
            case None => NotFound()
          }
        }
      }
    }

    val delete = DELETE(path("tasks" / Long)) { id =>
      store.get.flatMap { tasks =>
        tasks.get(id) match {
          case Some(_) =>
            store.update(_. - id).as(NoContent())
          case None =>
            NotFound()
        }
      }
    }

    health ++ getAll ++ create ++ getById ++ update ++ delete
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, TaskStore](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound
  }
}

object Main extends IOApp.Simple {
  val run: IO[ExitCode] = App.freshApp.flatMap { app =>
    EmberServerBuilder
      .default[IO]
      .withHost("0.0.0.0")
      .withPort(3000)
      .withHttpApp(app)
      .build
      .use(_ => IO.never)
      .as(ExitCode.Success)
  }
}
```

### FILE: main.test.scala
```scala
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe._
import io.circe.generic.auto._
import io.circe.syntax._

class ApiTests extends FunSuite {

  test("GET /health returns 200") {
    App.freshApp.flatMap { app =>
      val req = Request[IO](method = Method.GET, uri = uri"/health")
      app.run(req).use { resp =>
        IO(assertEquals(resp.status, Status.Ok))
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    App.freshApp.flatMap { app =>
      val req = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        entity = """{"title":"Test Task"}"""
      )
      app.run(req).use { resp =>
        for {
          _ <- IO(assertEquals(resp.status, Status.Created))
          task <- resp.as[Task]
          _ <- IO(assertEquals(task.id, 1L))
          _ <- IO(assertEquals(task.title, "Test Task"))
          _ <- IO(assertEquals(task.done, false))
        } yield ()
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    App.freshApp.flatMap { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        entity = """{"title":"Created"}"""
      )
      for {
        _ <- app.run(createReq).use(_ => IO.unit)
        getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
        resp <- app.run(getReq)
        task <- resp.as[Task]
        _ <- IO(assertEquals(resp.status, Status.Ok))
        _ <- IO(assertEquals(task.title, "Created"))
      } yield ()
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    App.freshApp.flatMap { app =>
      val req = Request[IO](method = Method.GET, uri = uri"/tasks/999")
      app.run(req).use { resp =>
        IO(assertEquals(resp.status, Status.NotFound))
      }
    }.unsafeRunSync()
  }

  test("DELETE existing task returns 204, subsequent GET returns 404") {
    App.freshApp.flatMap { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        entity = """{"title":"To Delete"}"""
      )
      for {
        _ <- app.run(createReq).use(_ => IO.unit)
        deleteReq = Request[IO](method = Method.DELETE, uri = uri"/tasks/1")
        delResp <- app.run(deleteReq)
        _ <- IO(assertEquals(delResp.status, Status.NoContent))
        getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
        getResp <- app.run(getReq)
        _ <- IO(assertEquals(getResp.status, Status.NotFound))
      } yield ()
    }.unsafeRunSync()
  }
}
```