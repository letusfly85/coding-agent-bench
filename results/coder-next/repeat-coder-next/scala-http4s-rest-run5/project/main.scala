//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

import cats.effect.{IO, Ref}
import cats.implicits._
import org.http4s._
import org.http4s.dsl._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)
case class CreateTask(title: String)

object Main extends IOApp.Simple {
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health = GET {
      Ok(Json.obj("status" -> Json.fromString("ok")))
    }

    val tasksList = GET {
      store.get.map { tasks =>
        val ordered = tasks.values.toList.sortBy(_.id)
        Ok(ordered.asJson)
      }
    }

    val tasksCreate = POST {
      entity[CreateTask].flatMap { createTask =>
        counter.modify { c =>
          val id = c + 1
          val task = Task(id, createTask.title, false)
          (id, task)
        }.flatMap { case (id, task) =>
          store.update(tasks => tasks + (id -> task)).as(
            Created(task.asJson)
          )
        }
      }
    }

    val taskGet = pathPrefix(Segment / "tasks" / Segment) { (idStr: String) =>
      idStr.toLongOption match {
        case Some(id) => GET {
          store.get.map { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task.asJson)
              case None => NotFound(Json.obj("error" -> Json.fromString(s"Task $id not found")))
            }
          }
        }
        case None => NotFound()
      }
    }

    val taskUpdate = pathPrefix(Segment / "tasks" / Segment) { (idStr: String) =>
      idStr.toLongOption match {
        case Some(id) => PUT {
          entity[CreateTask].flatMap { createTask =>
            store.modify { tasks =>
              tasks.get(id) match {
                case Some(task) =>
                  val updated = task.copy(title = createTask.title, done = true)
                  (updated, tasks + (id -> updated))
                case None =>
                  (null: Task, tasks) // dummy, will be handled by next step
              }
            }.flatMap {
              case (null, _) =>
                NotFound(Json.obj("error" -> Json.fromString(s"Task $id not found")))
              case (updated, _) =>
                Ok(updated.asJson)
            }
          }
        }
        case None => NotFound()
      }
    }

    val taskDelete = pathPrefix(Segment / "tasks" / Segment) { (idStr: String) =>
      idStr.toLongOption match {
        case Some(id) => DELETE {
          store.modify { tasks =>
            if (tasks.contains(id)) ((), tasks - id)
            else ((), tasks)
          }.flatMap {
            case () if tasks.contains(id) => NoContent()
            case () => NotFound(Json.obj("error" -> Json.fromString(s"Task $id not found")))
          }
        }
        case None => NotFound()
      }
    }

    health <+> tasksList <+> tasksCreate <+> taskGet <+> taskUpdate <+> taskDelete
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0)
    } yield routes(store, counter).orNotFound
  }

  def run: IO[Unit] =
    freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_.join)
    }
}
