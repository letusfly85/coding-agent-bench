//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package myapp

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import org.http4s._
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.*
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.circe.generic.semiauto.deriveEncoder

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    freshApp.flatMap(server => server.use(_ => IO.never))

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref[IO].of(Map.empty[Long, Task])
      counter <- Ref[IO].of(1L)
    yield routes(store, counter).orNotFound

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    val dsl = new Http4sDsl[IO]{}
    import dsl.*

    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(json"""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        for
          tasks <- store.get
          sorted = tasks.values.toList.sortBy(_.id)
        yield Ok(sorted.asJson)

      case req @ POST -> Root / "tasks" =>
        for
          title <- req.as[Task].map(_.title) // extract just the title from the request body
          id <- counter.getAndModify(_ + 1)
          task = Task(id, title, done = false)
          _ <- store.update(_.updated(task.id, task))
        yield Created(task.asJson)

      case GET -> Root / "tasks" / IntVar(id) =>
        for
          task <- store.get.map(_.get(id))
          result <- task match
            case Some(t) => Ok(t.asJson)
            case None => NotFound()
        yield result

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_) =>
              for
                newTask <- req.as[Task]
                _ <- store.update(_.updated(id, newTask.copy(id = id)))
              yield Ok(newTask.asJson)
            case None => NotFound()
        yield result

      case DELETE -> Root / "tasks" / IntVar(id) =>
        for
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_) =>
              for
                _ <- store.update(_.removed(id))
              yield NoContent()
            case None => NotFound()
        yield result
    }
