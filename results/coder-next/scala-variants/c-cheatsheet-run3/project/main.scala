//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package taskapi

import cats.effect.{IO, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._

import scala.collection.immutable

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val healthRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))
    }

    val tasksRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "tasks" =>
        store.get.map { tasks =>
          val ordered = tasks.values.toList.sortBy(_.id)
          Ok(ordered)
        }

      case req @ POST -> Root / "tasks" =>
        for {
          task <- req.as[Task]
          id <- counter.getAndUpdate(_ + 1)
          newTask = Task(id, task.title, false)
          _ <- store.update(_.updated(id, newTask))
        } yield Created(newTask)

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        for {
          task <- req.as[Task]
          updated <- store.modify { m =>
            m.get(id) match {
              case Some(_) =>
                val newTask = task.copy(id = id)
                (Ok(newTask), m.updated(id, newTask))
              case None =>
                (NotFound(), m)
            }
          }
        } yield updated

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.modify { m =>
          if (m.contains(id)) {
            (NoContent(), m - id)
          } else {
            (NotFound(), m)
          }
        }
    }

    healthRoute <+> tasksRoute
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](1L)
      routes = routes(store, counter)
    } yield routes.orNotFound
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
