//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package example

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.implicits._
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import io.circe.syntax._

import scala.concurrent.duration._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  def run: IO[Unit] =
    freshApp.flatMap(server => server.use(_ => IO.never))

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref[IO].of(Map.empty[Long, Task])
      counter <- Ref[IO].of(1L)
    } yield routes(store, counter).orNotFound
  }

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val dsl = Http4sDsl[IO]
    import dsl._

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        for {
          tasks <- store.get
          sortedTasks = tasks.values.toList.sortBy(_.id)
        } yield Ok(sortedTasks.asJson)

      case req @ POST -> Root / "tasks" =>
        for {
          title <- req.as[String]
          id <- counter.getAndUpdate(_ + 1)
          newTask = Task(id, title, done = false)
          _ <- store.update(_.updated(id, newTask))
        } yield Created(newTask.asJson)

      case GET -> Root / "tasks" / IntVar(id) =>
        for {
          tasks <- store.get
          task <- tasks.get(id) match {
            case Some(t) => Ok(t.asJson)
            case None => NotFound(Json.obj("error" -> Json.fromString(s"Task $id not found")))
          }
        } yield task

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for {
          tasks <- store.get
          task <- tasks.get(id) match {
            case Some(_) =>
              for {
                title <- req.as[String]
                updatedTask = Task(id, title, done = true)
                _ <- store.update(_.updated(id, updatedTask))
              } yield Ok(updatedTask.asJson)
            case None => NotFound(Json.obj("error" -> Json.fromString(s"Task $id not found")))
          }
        } yield task

      case DELETE -> Root / "tasks" / IntVar(id) =>
        for {
          tasks <- store.get
          _ <- tasks.get(id) match {
            case Some(_) => store.update(_.removed(id)) *> NoContent()
            case None => NotFound(Json.obj("error" -> Json.fromString(s"Task $id not found")))
          }
        } yield ()
    }
  }
}
