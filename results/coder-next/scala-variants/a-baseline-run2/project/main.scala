//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package com.example

import cats.effect.{IO, Ref}
import cats.syntax.all._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.dsl._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import io.circe.generic.auto._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  type TaskStore = Ref[IO, Map[Long, Task]]
  type IdCounter = Ref[IO, Long]

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root => Ok(json"""{"status":"ok"}""")
    }

    val tasks: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case req @ POST -> Root =>
        for {
          task <- req.as[Task]
          id <- counter.updateAndGet(_ + 1)
          newTask = Task(id, task.title, false)
          _ <- store.update(_.updated(id, newTask))
        } yield Created(newTask.asJson)

      case GET -> Root =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))

      case GET -> Root / IntVar(id) =>
        store.get.map(_.get(id))
          .flatMap {
            case Some(task) => Ok(task.asJson)
            case None => NotFound("Task not found")
          }

      case req @ PUT -> Root / IntVar(id) =>
        for {
          updatedTask <- req.as[Task]
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match {
            case Some(_) =>
              for {
                _ <- store.update(_.updated(id, updatedTask.copy(id = id)))
                updated <- store.get.map(_.get(id).get)
              } yield Ok(updated.asJson)
            case None => NotFound("Task not found")
          }
        } yield result

      case DELETE -> Root / IntVar(id) =>
        store.get.map(_.get(id))
          .flatMap {
            case Some(_) =>
              for {
                _ <- store.update(_.removed(id))
              } yield NoContent()
            case None => NotFound("Task not found")
          }
    }

    health <+> tasks
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0)
    } yield routes(store, counter).orNotFound
  }

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder.default[IO]
        .withHttpApp(app)
        .withPort(3000)
        .build
        .use(_ => IO.never)
    )
}
