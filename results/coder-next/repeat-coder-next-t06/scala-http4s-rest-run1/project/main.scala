//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package example

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import fs2.Stream
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl._
import org.http4s.server.middleware.Logger

import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root => Ok(Json.obj("status" -> Json.fromString("ok")))
    }

    val tasks: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))

      case req @ POST -> Root =>
        req.as[Task].flatMap { task =>
          counter.modify { n =>
            val newId = n + 1
            (newId, Task(newId, task.title, false))
          }.flatMap { newTask =>
            store.update(tasks => tasks + (newTask.id -> newTask)) *> Created(newTask.asJson)
          }
        }
    }

    val taskById: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case req @ GET -> Root / IntVar(id) =>
        store.get.map(_.get(id)).flatMap {
          case Some(task) => Ok(task.asJson)
          case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
        }

      case req @ PUT -> Root / IntVar(id) =>
        store.get.map(_.get(id)).flatMap {
          case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
          case Some(_) =>
            req.as[Task].flatMap { updatedTask =>
              store.modify { tasks =>
                tasks.get(id) match {
                  case Some(_) =>
                    val newTask = Task(id, updatedTask.title, updatedTask.done)
                    (tasks + (id -> newTask), newTask)
                  case None => (tasks, null.asInstanceOf[Task]) // unreachable
                }
              }.flatMap {
                case (_, null) => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
                case (_, task) => Ok(task.asJson)
              }
            }
        }

      case DELETE -> Root / IntVar(id) =>
        store.modify { tasks =>
          if (tasks.contains(id)) (tasks - id, true)
          else (tasks, false)
        }.flatMap {
          case true => NoContent()
          case false => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
        }
    }

    health <+> tasks <+> taskById
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
      routes = Logger.httpApp(routes(store, counter).orNotFound)
      app = routes
    } yield app
  }

  override def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("0.0.0.0").toOption.get)
        .withPort(Port.fromInt(3000).toOption.get)
        .withHttpApp(app)
        .build
        .use(_.join)
    )
}
