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
import org.http4s.circe._
import org.http4s.implicits._
import io.circe.generic.auto._

case class Task(id: Long, title: String, done: Boolean)
case class TaskCreateRequest(title: String)
case class TaskUpdateRequest(title: String, done: Boolean)

object Api {
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok("""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id)).flatMap(Ok(_))

      case req @ POST -> Root / "tasks" =>
        req.as[TaskCreateRequest].flatMap { input =>
          for {
            nextId <- counter.updateAndGet(_ + 1)
            task = Task(nextId, input.title, false)
            _ <- store.update(_.updated(nextId, task))
          } yield Created(task)
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { tasks =>
          tasks.get(id) match {
            case Some(t) => Ok(t)
            case None    => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[TaskUpdateRequest].flatMap { input =>
          store.get.flatMap { tasks =>
            tasks.get(id) match {
              case Some(existing) =>
                val updated = existing.copy(title = input.title, done = input.done)
                store.update(_.updated(id, updated)).as(Ok(updated))
              case None => NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { tasks =>
          tasks.get(id) match {
            case Some(_) => store.update(_. - id).as(NoContent())
            case None    => NotFound()
          }
        }
    }

  def freshApp: IO[HttpApp[IO]] = for {
    store <- Ref.of[IO, Map[Long, Task]](Map.empty)
    counter <- Ref.of[IO, Long](0L)
    app = routes(store, counter).orNotFound
  } yield app
}

object Main extends IOApp.Simple {
  val run = Api.freshApp.flatMap { app =>
    org.http4s.ember.server.EmberServerBuilder
      .default[IO]
      .withPort(3000)
      .withHttpApp(app)
      .build
      .useForever
  }
}
