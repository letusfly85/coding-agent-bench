//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
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

case class Task(id: Long, title: String, done: Boolean)
case class CreateTaskReq(title: String)
case class UpdateTaskReq(title: String, done: Boolean)

object Api:
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok("""{"status":"ok"}""")
      
      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.values.toList.sortBy(_.id).asJson))
      
      case req @ POST -> Root / "tasks" =>
        for
          body <- req.as[CreateTaskReq]
          id <- counter.updateAndGet(_ + 1)
          task = Task(id, body.title, done = false)
          _ <- store.update(_ + (id -> task))
        yield Created(task.asJson)
      
      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.map(_.get(id))
          .flatMap {
            case Some(task) => Ok(task.asJson)
            case None => NotFound()
          }
      
      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        for
          body <- req.as[UpdateTaskReq]
          taskOpt <- store.get.map(_.get(id))
          result <- taskOpt match {
            case Some(_) =>
              store.updateAndGet(_.updated(id, Task(id, body.title, body.done)))
                .map(task => Ok(task.asJson))
            case None =>
              NotFound().pure[IO]
          }
        yield result
      
      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.modify { map =>
          if (map.contains(id)) (map.removed(id), (true, Status.NoContent))
          else (map, (false, NotFound()))
        }.flatMap { (_, status) => status.pure[IO] }
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

object Main extends IOApp.Simple:
  val run: IO[Unit] =
    Api.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .useForever
    }
