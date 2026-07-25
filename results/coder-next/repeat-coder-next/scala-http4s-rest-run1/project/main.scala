//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.*
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto.*
import io.circe.syntax.*

import scala.concurrent.duration.*

case class Task(id: Long, title: String, done: Boolean)
case class CreateTask(title: String)

object Main extends IOApp.Simple {
  type Store = Ref[IO, Map[Long, Task]]
  type Counter = Ref[IO, Long]

  def routes(store: Store, counter: Counter): HttpRoutes[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl.*

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map { tasks =>
          val orderedTasks = tasks.values.toList.sortBy(_.id)
          Ok(orderedTasks.asJson)
        }

      case req @ POST -> Root / "tasks" =>
        for {
          createTask <- req.as[CreateTask]
          id <- counter.updateAndGet(_ + 1)
          task = Task(id, createTask.title, false)
          _ <- store.update(_.updated(id, task))
        } yield Created(task.asJson)

      case GET -> Root / "tasks" / IntVar(id) =>
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task.asJson)
            case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
          }
        }

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for {
          taskData <- req.as[Task]
          updated <- store.modify { tasks =>
            tasks.get(id) match {
              case Some(_) =>
                val newTasks = tasks.updated(id, taskData)
                (newTasks, true)
              case None =>
                (tasks, false)
            }
          }
          result <- if (updated) {
            store.get.map { tasks =>
              tasks.get(id) match {
                case Some(task) => Ok(task.asJson)
                case None => InternalServerError(Json.obj("error" -> Json.fromString("Failed to retrieve updated task")))
              }
            }
          } else {
            NotFound(Json.obj("error" -> Json.fromString("Task not found")))
          }
        } yield result

      case DELETE -> Root / "tasks" / IntVar(id) =>
        for {
          deleted <- store.modify { tasks =>
            if (tasks.contains(id)) {
              (tasks - id, true)
            } else {
              (tasks, false)
            }
          }
          result <- if (deleted) NoContent()
                    else NotFound(Json.obj("error" -> Json.fromString("Task not found")))
        } yield result
    }
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
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_.neverStop)
    )
}
