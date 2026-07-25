//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package myapp

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import fs2.Stream
import org.http4s._
import org.http4s.dsl._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.server.Server

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  type Store = Ref[IO, Map[Long, Task]]
  type Counter = Ref[IO, Long]

  def routes(store: Store, counter: Counter): HttpRoutes[IO] =
    val service = HttpRoutes.of[IO]:
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map { tasks =>
          val sortedTasks = tasks.values.toList.sortBy(_.id)
          Ok(sortedTasks.asJson)
        }

      case req @ POST -> Root / "tasks" =>
        for
          title <- req.as[String]
          id <- counter.updateAndModify(_ + 1L)
          task = Task(id, title, done = false)
          _ <- store.update(_ + (id -> task))
        yield Created(task.asJson, Headers(Header("Location", s"/tasks/$id")))

      case GET -> Root / "tasks" / IntVar(id) =>
        store.get.map { tasks =>
          tasks.get(id) match
            case Some(task) => Ok(task.asJson)
            case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
        }

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for
          title <- req.as[String]
          _ <- store.modify { tasks =>
            tasks.get(id) match
              case Some(task) =>
                val updated = task.copy(title = title, done = task.done)
                (tasks + (id -> updated), updated)
              case None => (tasks, null: Task)
          } match
            case null => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
            case task => Ok(task.asJson)

      case DELETE -> Root / "tasks" / IntVar(id) =>
        store.modify { tasks =>
          if tasks.contains(id) then
            (tasks - id, true)
          else
            (tasks, false)
        }.flatMap:
          case true => NoContent()
          case false => NotFound(Json.obj("error" -> Json.fromString("Task not found")))

    service

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder.default[IO]
        .withHttpApp(app)
        .withPort(3000)
        .build
        .use(_ => IO.never)
    )
