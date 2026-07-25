//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package myapp

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.*
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto.*
import io.circe.syntax.*

import scala.concurrent.ExecutionContext

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  type Store = Ref[IO, Map[Long, Task]]
  type Counter = Ref[IO, Long]

  def routes(store: Store, counter: Counter): HttpRoutes[IO] =
    val dsl = new Http4sDsl[IO]{}
    import dsl.*

    HttpRoutes.of[IO] {
      case GET -> Root =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" =>
        for
          title <- req.as[String]
          id <- counter.updateAndGet(_ + 1)
          task = Task(id, title, done = false)
          _ <- store.update(_ + (id -> task))
        yield Created(task.asJson)

      case GET -> Root / "tasks" / IntVar(id) =>
        store.get.map(_.get(id))
          .flatMap {
            case Some(task) => Ok(task.asJson)
            case None => NotFound()
          }

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for
          updated <- req.as[Task]
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_) =>
              store.update(tasks => tasks + (id -> updated))
              Ok(updated.asJson)
            case None => NotFound()
        yield result

      case DELETE -> Root / "tasks" / IntVar(id) =>
        for
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match
            case Some(_) =>
              store.update(tasks => tasks - id)
              NoContent()
            case None => NotFound()
        yield result
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder
        .default[IO]
        .withPort(3000)
        .withHttpApp(app)
        .build
        .use(_.compile.drain)
    )
