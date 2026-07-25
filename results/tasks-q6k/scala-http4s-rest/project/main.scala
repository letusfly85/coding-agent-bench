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
import io.circe.generic.auto._
import io.circe.Json

case class Task(id: Long, title: String, done: Boolean)

object Api {
  case class TaskCreateRequest(title: String)
  case class TaskUpdateRequest(title: String, done: Boolean)

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id).asJson).flatMap(Ok(_))

      case req @ POST -> Root / "tasks" =>
        req.as[TaskCreateRequest].flatMap { data =>
          counter.updateAndGet(_ + 1).flatMap { id =>
            val task = Task(id, data.title, done = false)
            store.update(_.updated(id, task)).as(Created(task))
          }
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[TaskUpdateRequest].flatMap { data =>
          store.get.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) =>
                val updated = task.copy(title = data.title, done = data.done)
                store.update(_.updated(id, updated)).as(Ok(updated))
              case None => NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { (prev, _) =>
          if (prev.contains(id)) NoContent()
          else NotFound()
        }
    }

  def freshApp: IO[HttpApp[IO]] = for {
    store <- Ref.of[IO, Map[Long, Task]](Map.empty)
    counter <- Ref.of[IO, Long](0L)
    app = routes(store, counter).orNotFound
  } yield app
}

object Main extends IOApp.Simple {
  val run: IO[Unit] = for {
    app <- Api.freshApp
    server <- org.http4s.ember.server.EmberServerBuilder[IO]
      .bindHttp(3000, "0.0.0.0")
      .withHttpApp(app)
      .build
    _ <- server.use(_ => IO.never)
  } yield ()
}
