//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

import cats.effect.{IO, IOApp}
import cats.data.OptionT
import cats.implicits.catsSyntaxOptionT
import fs2.Stream
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.dsl._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser._
import scala.concurrent.duration._

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple:
  type Store = Ref[IO, Map[Long, Task]]
  type Counter = Ref[IO, Long]

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    val httpRoutes = HttpRoutes.of[IO]:
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        for
          tasks <- store.get
          ordered = tasks.values.toList.sortBy(_.id)
        yield Ok(ordered.asJson)

      case req @ POST -> Root / "tasks" =>
        for
          titleOpt <- req.as[Map[String, String]].map(_.get("title"))
          title <- titleOpt.fold(BadRequest("Missing 'title' field"))(title => Ok(Json.obj("title" -> Json.fromString(title))))
          id <- counter.modify(old => (old + 1, old + 1))
          task = Task(id, title, false)
          _ <- store.update(_ + (id -> task))
        yield Created(task.asJson)

      case GET -> Root / "tasks" / IntVar(id) =>
        for
          task <- OptionT(store.get).flatMap(tasks => tasks.get(id))
        yield Ok(task.asJson)
        .getOrElse(NotFound())

      case req @ PUT -> Root / "tasks" / IntVar(id) =>
        for
          tasks <- store.get
          task <- OptionT.fromOption[IO](tasks.get(id))
          update <- req.as[Task]
          newTask = task.copy(title = update.title, done = update.done)
          _ <- store.update(_ + (id -> newTask))
        yield Ok(newTask.asJson)
        .getOrElse(NotFound())

      case DELETE -> Root / "tasks" / IntVar(id) =>
        for
          tasks <- store.get
          _ <- OptionT.fromOption[IO](tasks.get(id)).as(())
          _ <- store.update(_ - id)
        yield NoContent()
        .getOrElse(NotFound())

    httpRoutes

  def freshApp: IO[HttpApp[IO]] =
    for
      store <- Ref[IO].of(Map.empty[Long, Task])
      counter <- Ref[IO].of(1L)
    yield routes(store, counter).orNotFound

  def run: IO[Unit] =
    freshApp.flatMap(app =>
      EmberServerBuilder
        .default[IO]
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.forever)
    )
