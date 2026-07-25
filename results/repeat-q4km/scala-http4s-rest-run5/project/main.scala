//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)
case class TaskInput(title: String)

object TaskApi {

  def routes(
    store: Ref[IO, Map[Long, Task]],
    counter: Ref[IO, Long]
  ): HttpRoutes[IO] = {
    val r = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Map("status" -> "ok").asJson)

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.values.toSeq.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" if req.hasEntity =>
        req.as[TaskInput].flatMap { input =>
          for {
            id <- counter.updateAndGet(_ + 1)
            task = Task(id, input.title, done = false)
            _ <- store.update(_.updated(id, task))
          } yield Created(task.asJson)
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task.asJson)
            case None       => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) if req.hasEntity =>
        req.as[Task].flatMap { partial =>
          store.updateAndGet { tasks =>
            tasks.get(id) match {
              case Some(existing) =>
                tasks.updated(id, existing.copy(title = partial.title, done = partial.done))
              case None => tasks
            }
          }.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task.asJson)
              case None       => NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { tasks =>
          if (tasks.contains(id)) NoContent() else NotFound()
        }
    }
    r
  }

  def freshApp: IO[HttpApp[IO]] =
    for {
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound
}

object Main extends IOApp.Simple {
  val run: IO[Unit] =
    TaskApi.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost("0.0.0.0")
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_ => IO.never)
    }
}
