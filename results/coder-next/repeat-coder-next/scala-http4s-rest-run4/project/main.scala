//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package taskapi

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import org.http4s.dsl.*
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.circe.parser.*
import org.http4s.HttpApp

case class Task(id: Long, title: String, done: Boolean)

object TaskApi:
  def routes(store: Ref[IO, Vector[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val taskRoutes = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Map("status" -> "ok").asJson)

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" =>
        for {
          title <- req.as[String]
          id <- counter.modify(c => (c + 1, c + 1))
          task = Task(id, title, false)
          _ <- store.update(tasks => tasks :+ task)
          response <- Created(task.asJson)
        } yield response

      case GET -> Root / "tasks" / idVar =>
        for {
          id <- idVar.toIntOption match {
            case Some(i) => IO.pure(i.toLong)
            case None => NotFound()
          }
          task <- store.get.map(_.find(_.id == id))
          response <- task match {
            case Some(t) => Ok(t.asJson)
            case None => NotFound()
          }
        } yield response

      case req @ PUT -> Root / "tasks" / idVar =>
        for {
          id <- idVar.toIntOption match {
            case Some(i) => IO.pure(i.toLong)
            case None => NotFound()
          }
          taskOpt <- store.get.map(_.find(_.id == id))
          result <- taskOpt match {
            case Some(task) =>
              for {
                title <- req.as[String]
                done <- req.as[Map[String, Boolean]].map(_.getOrElse("done", false))
                newTask = task.copy(title = title, done = done)
                _ <- store.update(tasks => tasks.map(t => if (t.id == id) newTask else t))
              } yield Ok(newTask.asJson)
            case None => NotFound()
          }
        } yield result

      case DELETE -> Root / "tasks" / idVar =>
        for {
          id <- idVar.toIntOption match {
            case Some(i) => IO.pure(i.toLong)
            case None => NotFound()
          }
          taskOpt <- store.get.map(_.find(_.id == id))
          result <- taskOpt match {
            case Some(_) =>
              store.modify(tasks => (tasks.filterNot(_.id == id), ()))
              NoContent()
            case None => NotFound()
          }
        } yield result
    }

    taskRoutes
  }

  def freshApp: IO[HttpApp[IO]] =
    for {
      store <- Ref.of[IO, Vector[Task]](Vector.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound

@main
def main(): Unit =
  TaskApi.freshApp.flatMap { app =>
    EmberServerBuilder.default[IO]
      .withPort(3000)
      .withHttpApp(app)
      .build
      .use(_.compile.drain)
  }
