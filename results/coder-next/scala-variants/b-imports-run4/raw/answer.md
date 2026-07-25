### FILE: main.scala
```scala
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
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import munit.CatsEffectSuite
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.generic.auto._
import io.circe.syntax._

class MainTest extends CatsEffectSuite {
  import Main._

  test("GET /health returns 200") {
    for {
      app <- freshApp
      req = Request[IO](Method.GET, uri"/health")
      resp <- app.run(req)
      body <- resp.as[String]
    } assertEquals(resp.status, Status.Ok) *> assertEquals(body, """{"status":"ok"}""")
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      app <- freshApp
      body = Json.obj("title" -> Json.fromString("Test task"))
      req = Request[IO](Method.POST, uri"/tasks")
        .withEntity(body)
      resp <- app.run(req)
      task <- resp.as[Task]
    } assertEquals(resp.status, Status.Created) *> assertEquals(task.id, 1L) *> assertEquals(task.title, "Test task") *> assertEquals(task.done, false)
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      app <- freshApp
      // Create task first
      createBody = Json.obj("title" -> Json.fromString("First task"))
      createReq = Request[IO](Method.POST, uri"/tasks").withEntity(createBody)
      _ <- app.run(createReq)
      // Then get it
      getReq = Request[IO](Method.GET, uri"/tasks/1")
      resp <- app.run(getReq)
      task <- resp.as[Task]
    } assertEquals(resp.status, Status.Ok) *> assertEquals(task.id, 1L) *> assertEquals(task.title, "First task") *> assertEquals(task.done, false)
  }

  test("GET /tasks/999 returns 404") {
    for {
      app <- freshApp
      req = Request[IO](Method.GET, uri"/tasks/999")
      resp <- app.run(req)
    } assertEquals(resp.status, Status.NotFound)
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    for {
      app <- freshApp
      // Create task
      createBody = Json.obj("title" -> Json.fromString("To delete"))
      createReq = Request[IO](Method.POST, uri"/tasks").withEntity(createBody)
      _ <- app.run(createReq)
      // Delete task
      deleteReq = Request[IO](Method.DELETE, uri"/tasks/1")
      deleteResp <- app.run(deleteReq)
      // Verify deletion
      getReq = Request[IO](Method.GET, uri"/tasks/1")
      getResp <- app.run(getReq)
    } assertEquals(deleteResp.status, Status.NoContent) *> assertEquals(getResp.status, Status.NotFound)
  }
}
```