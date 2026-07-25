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

import scala.collection.mutable

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  type Store = Map[Long, Task]

  def routes(store: Ref[IO, Store], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val healthRoute = GET {
      Ok(Map("status" -> "ok").asJson)
    }

    val tasksRoutes = Path.of("tasks") / Segment[Long] { id =>
      // GET /tasks/{id}
      GET {
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task)
            case None       => NotFound()
          }
        }.flatten
      } |
      // PUT /tasks/{id}
      PUT {
        entity[Task].flatMap { task =>
          store.modify { tasks =>
            if (tasks.contains(id)) {
              val updatedTask = task.copy(id = id)
              (tasks.updated(id, updatedTask), Ok(updatedTask))
            } else {
              (tasks, NotFound())
            }
          }.flatten
        }
      } |
      // DELETE /tasks/{id}
      DELETE {
        store.modify { tasks =>
          val exists = tasks.contains(id)
          val newTasks = tasks - id
          (newTasks, if (exists) NoContent() else NotFound())
        }.flatten
      }
    } |
    // GET /tasks
    GET {
      store.get.map { tasks =>
        Ok(tasks.values.toList.sortBy(_.id).asJson)
      }.flatten
    } |
    // POST /tasks
    POST {
      entity[Task].flatMap { newTask =>
        counter.modify { n =>
          val newId = n + 1
          val task = Task(newId, newTask.title, done = false)
          (newId, task)
        }.flatMap { case (newId, task) =>
          store.update(tasks => tasks + (newId -> task))
            .map(_ => Created(task).withStatus(Status.Created))
        }
      }
    }
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Store](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    } yield HttpApp[IO](routes(store, counter).orNotFound)
  }

  def run: IO[Unit] =
    freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.join)
    }
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
import cats.effect.IO
import cats.effect.Ref
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.client.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax._
import io.circe.parser._
import com.comcast.ip4s._

import scala.collection.mutable

class MainSuite extends CatsEffectSuite {
  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      val req = Request[IO](Method.GET, uri"http://localhost/health")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        assertEquals(body, """{"status":"ok"}""")
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      val req = Request[IO](
        method = Method.POST,
        uri = uri"http://localhost/tasks",
        body = io.circe.syntax.asJson(Map("title" -> "Test task")).noSpaces.utf8Bytes
      )
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val body = resp.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(io.circe.Json.Null)
        assertEquals((json \ "id").as[Long].toOption.get, 1L)
        assertEquals((json \ "title").as[String].toOption.get, "Test task")
        assertEquals((json \ "done").as[Boolean].toOption.get, false)
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"http://localhost/tasks",
        body = io.circe.syntax.asJson(Map("title" -> "Get task")).noSpaces.utf8Bytes
      )
      app.run(createReq).flatMap { _ =>
        val getReq = Request[IO](Method.GET, uri"http://localhost/tasks/1")
        app.run(getReq).map { resp =>
          assertEquals(resp.status, Status.Ok)
          val body = resp.as[String].unsafeRunSync()
          val json = parse(body).getOrElse(io.circe.Json.Null)
          assertEquals((json \ "id").as[Long].toOption.get, 1L)
          assertEquals((json \ "title").as[String].toOption.get, "Get task")
          assertEquals((json \ "done").as[Boolean].toOption.get, false)
        }
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      val req = Request[IO](Method.GET, uri"http://localhost/tasks/999")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      // First create a task
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"http://localhost/tasks",
        body = io.circe.syntax.asJson(Map("title" -> "Delete me")).noSpaces.utf8Bytes
      )

      app.run(createReq).flatMap { _ =>
        // Delete the task
        val deleteReq = Request[IO](Method.DELETE, uri"http://localhost/tasks/1")
        app.run(deleteReq).map { resp =>
          assertEquals(resp.status, Status.NoContent)
        }
      }.flatMap { _ =>
        // Try to get the deleted task
        val getReq = Request[IO](Method.GET, uri"http://localhost/tasks/1")
        app.run(getReq).map { resp =>
          assertEquals(resp.status, Status.NotFound)
        }
      }
    }.unsafeRunSync()
  }
}
```