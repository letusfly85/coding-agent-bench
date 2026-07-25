//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package todoapi

import cats.effect.{IO, Ref}
import cats.effect.std.Ref
import cats.syntax.all._
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import io.circe.generic.auto._

import scala.concurrent.ExecutionContext

object todoapi {
  case class Task(id: Long, title: String, done: Boolean)
  case class CreateTaskRequest(title: String)
  case class UpdateTaskRequest(title: String, done: Boolean)

  def routes(store: Ref[IO, Vector[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val dsl = Http4sDsl[IO]
    import dsl._

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(json"""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.toList))

      case req @ POST -> Root / "tasks" =>
        req.as[CreateTaskRequest].flatMap { createReq =>
          counter.modify { n =>
            val newId = n + 1
            (newId, newId)
          }.flatMap { id =>
            store.update(tasks => tasks :+ Task(id, createReq.title, false)) *>
              Created(Task(id, createReq.title, false))
          }
        }

      case GET -> Root / "tasks" / IntVar(id) =>
        store.get.map(tasks => tasks.find(_.id == id))
          .flatMap {
            case Some(task) => Ok(task)
            case None => NotFound(json"""{"error":"not found"}""")
          }

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        req.as[UpdateTaskRequest].flatMap { updateReq =>
          store.modify { tasks =>
            tasks.patch(id - 1, Seq(Task(id, updateReq.title, updateReq.done)), 1) -> 
              tasks.patch(id - 1, Seq(Task(id, updateReq.title, updateReq.done)), 1).lift(id - 1)
          }.flatMap {
            case Some(task) => Ok(task)
            case None => NotFound(json"""{"error":"not found"}""")
          }
        }

      case DELETE -> Root / "tasks" / IntVar(id) =>
        store.modify { tasks =>
          if (tasks.length >= id && tasks(id - 1).id == id) {
            val (left, right) = tasks.splitAt(id - 1)
            (left ++ right.drop(1), true)
          } else {
            (tasks, false)
          }
        }.flatMap {
          case true => NoContent()
          case false => NotFound(json"""{"error":"not found"}""")
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Vector[Task]](Vector.empty)
      counter <- Ref.of[IO, Long](0)
      router = Router("/" -> routes(store, counter)).orNotFound
      app <- EmberServerBuilder
        .default[IO]
        .withPort(port"3000")
        .withHttpApp(router)
        .build
    } yield app
  }

  object Main extends IOApp.Simple {
    def run: IO[Unit] = freshApp.flatMap(_.use(_.neverRun))
  }
}
