//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import cats.effect.{IO, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  type TaskStore = Ref[IO, Map[Long, Task]]
  type IdCounter = Ref[IO, Long]

  def routes(store: TaskStore, counter: IdCounter): HttpRoutes[IO] =
    val taskService = new:
      def getTasks: IO[Response[IO]] =
        store.get.map(tasks => JsonArray(tasks.values.toList.sortBy(_.id).map(_.asJson))).flatMap(Ok(_))

      def createTask(req: Request[IO]): IO[Response[IO]] =
        req.as[TaskCopy].flatMap { copy =>
          counter.modify(n => (n + 1, n + 1)).flatMap { id =>
            val task = Task(id, copy.title, false)
            store.update(_.updated(id, task))
            Created(task)
          }
        }

      def getTask(id: Long): IO[Response[IO]] =
        store.get.map(_.get(id)).flatMap {
          case Some(task) => Ok(task)
          case None => NotFound()
        }

      def updateTask(id: Long, req: Request[IO]): IO[Response[IO]] =
        store.get.flatMap { tasks =>
          tasks.get(id) match
            case Some(_) =>
              req.as[TaskCopy].flatMap { copy =>
                val updated = Task(id, copy.title, copy.done)
                store.update(_.updated(id, updated)).map(_ => Ok(updated))
              }
            case None => NotFound()
        }

      def deleteTask(id: Long): IO[Response[IO]] =
        store.modify { tasks =>
          if tasks.contains(id) then (tasks - id, true)
          else (tasks, false)
        }.flatMap {
          case true => NoContent()
          case false => NotFound()
        }

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> "ok".asJson))

      case GET -> Root / "tasks" =>
        taskService.getTasks

      case req @ POST -> Root / "tasks" =>
        taskService.createTask(req)

      case GET -> Root / "tasks" / idVar =>
        idVar.toLongOption match
          case Some(id) => taskService.getTask(id)
          case None => NotFound()

      case req @ PUT -> Root / "tasks" / idVar =>
        idVar.toLongOption match
          case Some(id) => taskService.updateTask(id, req)
          case None => NotFound()

      case DELETE -> Root / "tasks" / idVar =>
        idVar.toLongOption match
          case Some(id) => taskService.deleteTask(id)
          case None => NotFound()
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

  override def run: IO[Unit] =
    freshApp.use { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.forever)
    }

// Helper for parsing task creation/update bodies
case class TaskCopy(title: String, done: Boolean)
object TaskCopy:
  given Decoder[TaskCopy] = Decoder.forProduct2("title", "done")(TaskCopy.apply)
  given Encoder[TaskCopy] = Encoder.forProduct2("title", "done") { tc => (tc.title, tc.done) }
