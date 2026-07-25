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
        Ok(Json.obj("status" -> Json.fromString("ok")))
        
      case GET -> Root / "tasks" => 
        store.get.map(tasks => 
          Ok(tasks.values.toList.sortBy(_.id).asJson)
        )
        
      case req @ POST -> Root / "tasks" => 
        for {
          CreateTaskReq(title) <- req.as[CreateTaskReq]
          id <- counter.updateAndGet(_ + 1)
          task = Task(id, title, done = false)
          _ <- store.update(_.updated(id, task))
          response <- Created(task)
        } yield response
        
      case GET -> Root / "tasks" / LongVar(id) => 
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task)
            case None => NotFound()
          }
        }
        
      case req @ PUT -> Root / "tasks" / LongVar(id) => 
        for {
          UpdateTaskReq(title, done) <- req.as[UpdateTaskReq]
          task <- store.modify { tasks =>
            tasks.get(id) match {
              case Some(_) => 
                val updatedTask = Task(id, title, done)
                (tasks.updated(id, updatedTask), Some(updatedTask))
              case None => 
                (tasks, None)
            }
          }
          response <- task match {
            case Some(updatedTask) => Ok(updatedTask)
            case None => NotFound()
          }
        } yield response
        
      case DELETE -> Root / "tasks" / LongVar(id) => 
        store.modify { tasks =>
          tasks.get(id) match {
            case Some(_) => 
              (tasks - id, Status.NoContent)
            case None => 
              (tasks, Status.NotFound)
          }
        }.map(response => response)
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
