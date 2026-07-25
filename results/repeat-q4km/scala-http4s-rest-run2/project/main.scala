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
