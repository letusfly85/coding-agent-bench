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

import java.util.concurrent.atomic.AtomicLong

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  def routes(store: Ref[IO, List[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val taskService = new {
      val httpRoutes = Routes(
        GET(Path / "health") {
          Ok(Json.obj("status" -> Json.fromString("ok")))
        },
        GET(Path / "tasks") {
          store.get.map { tasks =>
            Ok(tasks.sortBy(_.id).asJson)
          }
        },
        POST(Path / "tasks") {
          for {
            title <- req => req.as[String]
            id <- counter.getAndModify(_ + 1)
            task = Task(id, title, done = false)
            _ <- store.update(tasks => task :: tasks)
          } yield Created(task.asJson)
        },
        GET(Path / "tasks" / IntVar(id)) {
          store.get.map { tasks =>
            tasks.find(_.id == id) match {
              case Some(task) => Ok(task.asJson)
              case None => NotFound()
            }
          }
        },
        PUT(Path / "tasks" / IntVar(id)) {
          for {
            taskUpdate <- req => req.as[Task]
            _ <- store.modify { tasks =>
              tasks.find(_.id == id) match {
                case Some(_) =>
                  val updated = taskUpdate.copy(id = id)
                  (updated :: tasks.filterNot(_.id == id), updated)
                case None => (tasks, null)
              }
            } flatMap {
              case null => NotFound().pure[IO]
              case updated => Ok(updated.asJson).pure[IO]
            }
          } yield ()
        },
        DELETE(Path / "tasks" / IntVar(id)) {
          store.modify { tasks =>
            val (found, remaining) = tasks.partition(_.id == id)
            if (found.nonEmpty) (remaining, Status.NoContent)
            else (tasks, Status.NotFound)
          }.flatMap(_.pure[IO])
        }
      )
    }.httpRoutes

    taskService
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, List[Task]](List.empty)
      counter <- Ref.of[IO, Long](1L)
      router = routes(store, counter)
      app = router.orNotFound
    } yield app
  }

  def run: IO[Unit] =
    freshApp.flatMap(app => 
      EmberServerBuilder
        .default[IO]
        .withHost(ip"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.useForever)
    )
}
