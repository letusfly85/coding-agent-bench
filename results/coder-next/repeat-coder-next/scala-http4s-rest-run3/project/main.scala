//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package example

import cats.effect.{IO, Ref}
import cats.implicits.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.*
import io.circe.generic.auto.*

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    freshApp.flatMap(EmberServerBuilder.default[IO].build).use(_.join)

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        for
          tasks <- store.get
          ordered = tasks.values.toList.sortBy(_.id)
        yield Ok(ordered.asJson)

      case req @ POST -> Root / "tasks" =>
        for
          task <- req.as[Task].handleErrorWith(_ => BadRequest("Missing or invalid title"))
          newId <- counter.modify(id => (id + 1, id + 1))
          newTask = Task(newId, task.title, done = false)
          _ <- store.update(_ + (newId -> newTask))
        yield Created(newTask.asJson, Location(Uri(path = s"/tasks/${newId}")))

      case GET -> Root / "tasks" / idPath =>
        for
          id <- idPath.toLongOption match
            case Some(i) => IO.pure(i)
            case None => NotFound(Json.obj("error" -> Json.fromString("Invalid id")))
          task <- store.get.map(_.get(id))
        yield task match
          case Some(t) => Ok(t.asJson)
          case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))

      case req @ PUT -> Root / "tasks" / idPath =>
        for
          id <- idPath.toLongOption match
            case Some(i) => IO.pure(i)
            case None => NotFound(Json.obj("error" -> Json.fromString("Invalid id")))
          updatedTask <- req.as[Task].handleErrorWith(_ => BadRequest("Missing or invalid task data"))
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_oldTask) =>
              val newTask = Task(id, updatedTask.title, updatedTask.done)
              _ <- store.update(_ + (id -> newTask))
              IO.pure(Ok(newTask.asJson))
            case None => IO.pure(NotFound(Json.obj("error" -> Json.fromString("Task not found"))))
        yield result

      case DELETE -> Root / "tasks" / idPath =>
        for
          id <- idPath.toLongOption match
            case Some(i) => IO.pure(i)
            case None => NotFound(Json.obj("error" -> Json.fromString("Invalid id")))
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_) =>
              _ <- store.update(_ - id)
              IO.pure(NoContent())
            case None => IO.pure(NotFound(Json.obj("error" -> Json.fromString("Task not found"))))
        yield result
