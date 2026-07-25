//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.syntax.all._
import io.circe.generic.auto._

case class Task(id: Long, title: String, done: Boolean)
case class CreateTask(title: String)
case class UpdateTask(title: String, done: Boolean)

object App {
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "health" =>
        Ok("""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id)).flatMap(tasks => Ok(tasks))

      case req @ POST -> Root / "tasks" =>
        req.as[CreateTask].flatMap { ct =>
          for {
            id <- counter.getAndUpdate(_ + 1)
            task = Task(id, ct.title, false)
            _ <- store.update(_.updated(id, task))
          } yield Created(task)
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { map =>
          map.get(id) match {
            case Some(task) => Ok(task)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[UpdateTask].flatMap { ut =>
          store.get.flatMap { map =>
            map.get(id) match {
              case Some(existing) =>
                val updated = existing.copy(title = ut.title, done = ut.done)
                store.update(_.updated(id, updated)).as(Ok(updated))
              case None =>
                NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.getAndUpdate(_. - id).flatMap { oldMap =>
          if (oldMap.contains(id)) NoContent() else NotFound()
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] = for {
    store <- Ref.of[IO, Map[Long, Task]](Map.empty)
    counter <- Ref.of[IO, Long](1L)
  } yield routes(store, counter).orNotFound
}

object Main extends IOApp.Simple {
  val run = App.freshApp.flatMap { app =>
    org.http4s.ember.server.EmberServerBuilder[IO]
      .bindHttp(3000, "0.0.0.0")
      .withHttpApp(app)
      .build
      .useForever
  }
}
