### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package taskapi

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import org.http4s.dsl.*
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.circe.parser.*
import org.http4s.HttpApp

case class Task(id: Long, title: String, done: Boolean)

object TaskApi:
  def routes(store: Ref[IO, Vector[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val taskRoutes = HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Map("status" -> "ok").asJson)

      case GET -> Root / "tasks" =>
        store.get.map(tasks => Ok(tasks.sortBy(_.id).asJson))

      case req @ POST -> Root / "tasks" =>
        for {
          title <- req.as[String]
          id <- counter.modify(c => (c + 1, c + 1))
          task = Task(id, title, false)
          _ <- store.update(tasks => tasks :+ task)
          response <- Created(task.asJson)
        } yield response

      case GET -> Root / "tasks" / idVar =>
        for {
          id <- idVar.toIntOption match {
            case Some(i) => IO.pure(i.toLong)
            case None => NotFound()
          }
          task <- store.get.map(_.find(_.id == id))
          response <- task match {
            case Some(t) => Ok(t.asJson)
            case None => NotFound()
          }
        } yield response

      case req @ PUT -> Root / "tasks" / idVar =>
        for {
          id <- idVar.toIntOption match {
            case Some(i) => IO.pure(i.toLong)
            case None => NotFound()
          }
          taskOpt <- store.get.map(_.find(_.id == id))
          result <- taskOpt match {
            case Some(task) =>
              for {
                title <- req.as[String]
                done <- req.as[Map[String, Boolean]].map(_.getOrElse("done", false))
                newTask = task.copy(title = title, done = done)
                _ <- store.update(tasks => tasks.map(t => if (t.id == id) newTask else t))
              } yield Ok(newTask.asJson)
            case None => NotFound()
          }
        } yield result

      case DELETE -> Root / "tasks" / idVar =>
        for {
          id <- idVar.toIntOption match {
            case Some(i) => IO.pure(i.toLong)
            case None => NotFound()
          }
          taskOpt <- store.get.map(_.find(_.id == id))
          result <- taskOpt match {
            case Some(_) =>
              store.modify(tasks => (tasks.filterNot(_.id == id), ()))
              NoContent()
            case None => NotFound()
          }
        } yield result
    }

    taskRoutes
  }

  def freshApp: IO[HttpApp[IO]] =
    for {
      store <- Ref.of[IO, Vector[Task]](Vector.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield routes(store, counter).orNotFound

@main
def main(): Unit =
  TaskApi.freshApp.flatMap { app =>
    EmberServerBuilder.default[IO]
      .withPort(3000)
      .withHttpApp(app)
      .build
      .use(_.compile.drain)
  }
```

### FILE: main.test.scala
```scala
//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4

package taskapi

import munit.*
import cats.effect.{IO, Resource}
import org.http4s.*
import org.http4s.dsl.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.parser.*
import io.circe.syntax.*

class TaskApiTest extends FunSuite:
  test("GET /health returns 200") {
    TaskApi.freshApp
      .flatMap(app => app.run(Request[IO](Method.GET, uri"/health")))
      .map { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.as[String].unsafeRunSync(), """{"status":"ok"}""")
      }
  }

  test("POST /tasks returns 201 and id 1") {
    TaskApi.freshApp
      .flatMap { app =>
        val req = Request[IO](Method.POST, uri"/tasks")
          .withEntity("""{"title":"First task"}""")
        app.run(req)
      }
      .map { resp =>
        assertEquals(resp.status, Status.Created)
        val json = parse(resp.as[String].unsafeRunSync()).leftMap(throw _).merge
        assertEquals((json \ "id").as[Long].toOption, Some(1L))
        assertEquals((json \ "title").as[String].toOption, Some("First task"))
        assertEquals((json \ "done").as[Boolean].toOption, Some(false))
      }
  }

  test("GET /tasks/1 after creation returns the task") {
    TaskApi.freshApp
      .flatMap { app =>
        for {
          _ <- app.run(
            Request[IO](Method.POST, uri"/tasks")
              .withEntity("""{"title":"First task"}""")
          )
          resp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
        } yield resp
      }
      .map { resp =>
        assertEquals(resp.status, Status.Ok)
        val json = parse(resp.as[String].unsafeRunSync()).leftMap(throw _).merge
        assertEquals((json \ "id").as[Long].toOption, Some(1L))
        assertEquals((json \ "title").as[String].toOption, Some("First task"))
        assertEquals((json \ "done").as[Boolean].toOption, Some(false))
      }
  }

  test("GET /tasks/999 returns 404") {
    TaskApi.freshApp
      .flatMap(app => app.run(Request[IO](Method.GET, uri"/tasks/999")))
      .map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    TaskApi.freshApp
      .flatMap { app =>
        for {
          _ <- app.run(
            Request[IO](Method.POST, uri"/tasks")
              .withEntity("""{"title":"Delete me"}""")
          )
          deleteResp <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
          getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
        } yield (deleteResp, getResp)
      }
      .map { case (deleteResp, getResp) =>
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
  }
```