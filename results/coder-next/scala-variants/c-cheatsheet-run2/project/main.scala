//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._

import scala.collection.mutable

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  type TaskStore = Map[Long, Task]

  def routes(store: Ref[IO, TaskStore], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val taskService = new {
      val http4sDsl = Http4sDsl[IO]
      import http4sDsl._

      def getTask(id: Long): F[Option[Task]] = store.get.map(_.get(id))

      def createTask(title: String): F[Task] = counter.modify { c =>
        val newId = c + 1
        val task = Task(newId, title, done = false)
        (task, newId)
      }.flatMap { case (task, _) =>
        store.update(tasks => tasks + (task.id -> task)) *> IO.pure(task)
      }

      def updateTask(id: Long, title: String, done: Boolean): F[Option[Task]] =
        store.modify { tasks =>
          tasks.get(id) match {
            case Some(_) =>
              val updated = Task(id, title, done)
              (Some(updated), tasks + (id -> updated))
            case None =>
              (None, tasks)
          }
        }

      def deleteTask(id: Long): F[Boolean] =
        store.modify { tasks =>
          val existed = tasks.contains(id)
          (existed, tasks - id)
        }
    }

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map { tasks =>
          val ordered = tasks.values.toList.sortBy(_.id)
          Ok(ordered.asJson)
        }

      case req @ POST -> Root / "tasks" =>
        req.as[Task].flatMap { task =>
          createTask(task.title).map { created =>
            Created(created.asJson)
          }
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        getTask(id).flatMap {
          case Some(task) => Ok(task.asJson)
          case None       => NotFound()
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[Task].flatMap { task =>
          updateTask(id, task.title, task.done).flatMap {
            case Some(updated) => Ok(updated.asJson)
            case None          => NotFound()
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        deleteTask(id).flatMap {
          case true  => NoContent()
          case false => NotFound()
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, TaskStore](Map.empty)
      counter <- Ref.of[IO, Long](0L)
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
