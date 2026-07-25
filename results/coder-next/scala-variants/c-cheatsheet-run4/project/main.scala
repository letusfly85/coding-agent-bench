//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._
import scala.collection.concurrent.TrieMap

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  type TaskStore = Map[Long, Task]

  def routes(store: Ref[IO, TaskStore], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val healthRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))
    }

    val tasksRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" =>
        for {
          task <- req.as[Task]
          id <- counter.getAndUpdate(_ + 1)
          newTask = Task(id, task.title, false)
          _ <- store.update(tasks => tasks + (id -> newTask))
        } yield Created(newTask.asJson)

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task.asJson)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        for {
          task <- req.as[Task]
          updated <- store.modify { tasks =>
            tasks.get(id) match {
              case Some(_) =>
                val newTask = Task(id, task.title, task.done)
                (tasks + (id -> newTask), true)
              case None =>
                (tasks, false)
            }
          }
          result <- if (updated) {
            store.get.map(_.get(id).map(t => Ok(t.asJson)).getOrElse(NotFound()))
          } else {
            IO.pure(NotFound())
          }
        } yield result

      case DELETE -> Root / "tasks" / LongVar(id) =>
        for {
          deleted <- store.modify { tasks =>
            (tasks - id, tasks.contains(id))
          }
          result <- if (deleted) {
            IO.pure(NoContent())
          } else {
            IO.pure(NotFound())
          }
        } yield result
    }

    healthRoute <+> tasksRoute
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, TaskStore](Map.empty)
      counter <- Ref.of[IO, Long](1L)
    } yield routes(store, counter).orNotFound
  }

  def run: IO[Unit] =
    freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.forever)
    }
}
