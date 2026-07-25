//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.dsl.impl._
import org.http4s.ember.server._
import org.http4s.implicits._
import org.http4s.syntax._
import org.http4s.Uri._
import org.http4s.Method._
import org.http4s.Status._
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)
case class TaskInput(title: String)

object TaskApi {
  type Store = Map[Long, Task]

  def routes(
    store: Ref[IO, Store],
    counter: Ref[IO, Long]
  ): HttpRoutes[IO] = {

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok("""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id)).flatMap { tasks =>
          Ok(tasks.asJson)
        }

      case req @ POST -> Root / "tasks" =>
        req.as[TaskInput].flatMap { input =>
          counter.updateAndGet(_ + 1).flatMap { id =>
            val task = Task(id, input.title, done = false)
            store.update(_.updated(id, task)) >> Created(task)
          }
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { s =>
          s.get(id) match {
            case Some(task) => Ok(task)
            case None       => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[Task].flatMap { updatedTask =>
          store.get.flatMap { s =>
            if (s.contains(id)) {
              val taskWithId = updatedTask.copy(id = id)
              store.update(_.updated(id, taskWithId)) >> Ok(taskWithId)
            } else {
              NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { prev =>
          if (prev.contains(id)) NoContent()
          else NotFound()
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] =
    (Ref.of[IO, Store](Map.empty), Ref.of[IO, Long](0L)).mapN { (store, counter) =>
      routes(store, counter).orNotFound
    }
}

object Main extends IOApp.Simple {
  val run: IO[ExitCode] =
    TaskApi.freshApp.flatMap { app =>
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
