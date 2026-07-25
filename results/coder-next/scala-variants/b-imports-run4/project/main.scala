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

import scala.collection.mutable.ListBuffer

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  import Main._

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, ListBuffer[Task]](ListBuffer.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound
  }

  def routes(store: Ref[IO, ListBuffer[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val taskRoutes = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        for {
          tasks <- store.get
          ordered = tasks.toList.sortBy(_.id)
        } Ok(ordered)

      case req @ POST -> Root / "tasks" =>
        for {
          task <- req.as[Task]
          id <- counter.getAndUpdate(_ + 1)
          newTask = Task(id + 1, task.title, false)
          _ <- store.update(_.append(newTask))
        } Created(newTask)

      case GET -> Root / "tasks" / idPath =>
        for {
          id <- parseId(idPath)
          tasks <- store.get
          task <- tasks.find(_.id == id) match {
            case Some(t) => Ok(t)
            case None => NotFound()
          }
        } task

      case req @ PUT -> Root / "tasks" / idPath =>
        for {
          id <- parseId(idPath)
          updatedTask <- req.as[Task]
          tasks <- store.get
          result <- tasks.find(_.id == id) match {
            case Some(oldTask) =>
              val newTask = oldTask.copy(title = updatedTask.title, done = updatedTask.done)
              store.update(_.map { t =>
                if (t.id == id) newTask else t
              })
              Ok(newTask)
            case None => NotFound()
          }
        } result

      case DELETE -> Root / "tasks" / idPath =>
        for {
          id <- parseId(idPath)
          tasks <- store.get
          found <- tasks.find(_.id == id) match {
            case Some(_) => 
              store.update(_.filter(_.id != id))
              NoContent()
            case None => NotFound()
          }
        } found
    }

    taskRoutes
  }

  private def parseId(path: String): IO[Long] =
    path.toLongOption match {
      case Some(id) => id.pure[IO]
      case None => BadRequest("Invalid task id").raiseError[IO, Long]
    }

  override def run: IO[Unit] =
    freshApp.flatMap(app => EmberServerBuilder[IO]
      .withPort(port"3000")
      .withHttpApp(app)
      .build
      .use(_.forever)
    )
}
