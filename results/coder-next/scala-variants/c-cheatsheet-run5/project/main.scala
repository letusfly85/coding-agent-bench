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
  type Store = Map[Long, Task]

  def routes(store: Ref[IO, Store], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val healthRoute = GET {
      Ok(Map("status" -> "ok").asJson)
    }

    val tasksRoutes = Path.of("tasks") / Segment[Long] { id =>
      // GET /tasks/{id}
      GET {
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task)
            case None       => NotFound()
          }
        }.flatten
      } |
      // PUT /tasks/{id}
      PUT {
        entity[Task].flatMap { task =>
          store.modify { tasks =>
            if (tasks.contains(id)) {
              val updatedTask = task.copy(id = id)
              (tasks.updated(id, updatedTask), Ok(updatedTask))
            } else {
              (tasks, NotFound())
            }
          }.flatten
        }
      } |
      // DELETE /tasks/{id}
      DELETE {
        store.modify { tasks =>
          val exists = tasks.contains(id)
          val newTasks = tasks - id
          (newTasks, if (exists) NoContent() else NotFound())
        }.flatten
      }
    } |
    // GET /tasks
    GET {
      store.get.map { tasks =>
        Ok(tasks.values.toList.sortBy(_.id).asJson)
      }.flatten
    } |
    // POST /tasks
    POST {
      entity[Task].flatMap { newTask =>
        counter.modify { n =>
          val newId = n + 1
          val task = Task(newId, newTask.title, done = false)
          (newId, task)
        }.flatMap { case (newId, task) =>
          store.update(tasks => tasks + (newId -> task))
            .map(_ => Created(task).withStatus(Status.Created))
        }
      }
    }
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Store](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield HttpApp[IO](routes(store, counter).orNotFound)
  }

  def run: IO[Unit] =
    freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.join)
    }
}
