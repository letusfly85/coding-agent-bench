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
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)
case class CreateTaskReq(title: String)
case class UpdateTaskReq(title: String, done: Boolean)

object Api {
  def routes(
      store: Ref[IO, Map[Long, Task]],
      counter: Ref[IO, Long]
  ): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok("""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id).asJson)

      case req @ POST -> Root / "tasks" =>
        req.as[CreateTaskReq].flatMap { input =>
          counter.modify(c => (c + 1, c + 1)).flatMap { id =>
            val task = Task(id, input.title, done = false)
            store.update(_.updated(id, task))
            Created(task)
          }
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { map =>
          map.get(id) match {
            case Some(task) => Ok(task)
            case None       => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[UpdateTaskReq].flatMap { input =>
          store.updateAndGet { map =>
            map.get(id) match {
              case Some(_) => map.updated(id, Task(id, input.title, input.done))
              case None    => map
            }
          }.flatMap { map =>
            if (map.contains(id)) Ok(map(id))
            else NotFound()
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { map =>
          if (map.contains(id)) NoContent()
          else NotFound()
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound
  }
}

object Main extends IOApp.Simple {
  val run: IO[Unit] = Api.freshApp.flatMap { app =>
    EmberServerBuilder
      .default[IO]
      .withHost("0.0.0.0")
      .withPort(3000)
      .withHttpApp(app)
      .build
      .useForever
  }
}
