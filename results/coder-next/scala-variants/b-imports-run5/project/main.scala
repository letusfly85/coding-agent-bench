//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._
import io.circe.syntax._
import io.circe.parser._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  import Task._

  def routes(store: Ref[IO, List[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root => Ok(json"""{"status":"ok"}""")
    }

    val tasks: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case req @ GET -> Root =>
        store.get.map(tasks => Ok(tasks.sortBy(_.id).asJson))

      case req @ POST -> Root =>
        for {
          task <- req.as[Task]
          id <- counter.updateAndGet(_ + 1)
          newTask = Task(id, task.title, false)
          _ <- store.update(newTask :: _)
        } yield Created(newTask.asJson)

      case GET -> Root / IntVar(id) =>
        store.get.map(_.find(_.id == id))
          .flatMap {
            case Some(task) => Ok(task.asJson)
            case None => NotFound()
          }

      case req @ PUT -> Root / IntVar(id) =>
        for {
          updated <- req.as[Task]
          tasks <- store.get
          result <- tasks.find(_.id == id) match {
            case Some(task) =>
              val newTask = task.copy(title = updated.title, done = updated.done)
              store.update(newTask :: tasks.filterNot(_.id == id)) *> Ok(newTask.asJson)
            case None => NotFound()
          }
        } yield result

      case DELETE -> Root / IntVar(id) =>
        store.modify { tasks =>
          val (found, remaining) = tasks.partition(_.id == id)
          (remaining, found.nonEmpty)
        }.flatMap {
          case true => NoContent()
          case false => NotFound()
        }
    }

    health <+> tasks
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, List[Task]](List.empty)
      counter <- Ref.of[IO, Long](0)
      routesApp = routes(store, counter).orNotFound
      app = HttpApp[IO](routesApp)
    } yield app
  }

  def run: IO[Unit] =
    freshApp.flatMap(app => EmberServerBuilder[IO]
      .withHost(ip"0.0.0.0")
      .withPort(port"3000")
      .withHttpApp(app)
      .build
      .use(_.forever)
    )
}
