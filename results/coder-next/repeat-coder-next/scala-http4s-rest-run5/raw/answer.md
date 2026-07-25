### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

import cats.effect.{IO, Ref}
import cats.implicits._
import org.http4s._
import org.http4s.dsl._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import io.circe.generic.auto._
import io.circe.syntax._

case class Task(id: Long, title: String, done: Boolean)
case class CreateTask(title: String)

object Main extends IOApp.Simple {
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val health = GET {
      Ok(Json.obj("status" -> Json.fromString("ok")))
    }

    val tasksList = GET {
      store.get.map { tasks =>
        val ordered = tasks.values.toList.sortBy(_.id)
        Ok(ordered.asJson)
      }
    }

    val tasksCreate = POST {
      entity[CreateTask].flatMap { createTask =>
        counter.modify { c =>
          val id = c + 1
          val task = Task(id, createTask.title, false)
          (id, task)
        }.flatMap { case (id, task) =>
          store.update(tasks => tasks + (id -> task)).as(
            Created(task.asJson)
          )
        }
      }
    }

    val taskGet = pathPrefix(Segment / "tasks" / Segment) { (idStr: String) =>
      idStr.toLongOption match {
        case Some(id) => GET {
          store.get.map { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task.asJson)
              case None => NotFound(Json.obj("error" -> Json.fromString(s"Task $id not found")))
            }
          }
        }
        case None => NotFound()
      }
    }

    val taskUpdate = pathPrefix(Segment / "tasks" / Segment) { (idStr: String) =>
      idStr.toLongOption match {
        case Some(id) => PUT {
          entity[CreateTask].flatMap { createTask =>
            store.modify { tasks =>
              tasks.get(id) match {
                case Some(task) =>
                  val updated = task.copy(title = createTask.title, done = true)
                  (updated, tasks + (id -> updated))
                case None =>
                  (null: Task, tasks) // dummy, will be handled by next step
              }
            }.flatMap {
              case (null, _) =>
                NotFound(Json.obj("error" -> Json.fromString(s"Task $id not found")))
              case (updated, _) =>
                Ok(updated.asJson)
            }
          }
        }
        case None => NotFound()
      }
    }

    val taskDelete = pathPrefix(Segment / "tasks" / Segment) { (idStr: String) =>
      idStr.toLongOption match {
        case Some(id) => DELETE {
          store.modify { tasks =>
            if (tasks.contains(id)) ((), tasks - id)
            else ((), tasks)
          }.flatMap {
            case () if tasks.contains(id) => NoContent()
            case () => NotFound(Json.obj("error" -> Json.fromString(s"Task $id not found")))
          }
        }
        case None => NotFound()
      }
    }

    health <+> tasksList <+> tasksCreate <+> taskGet <+> taskUpdate <+> taskDelete
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0)
    } yield routes(store, counter).orNotFound
  }

  def run: IO[Unit] =
    freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withPort(3000)
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

import munit._
import cats.effect.IO
import org.http4s._
import org.http4s.dsl._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import io.circe.syntax._
import io.circe.parser._

class MainTest extends FunSuite {
  private def buildApp = Main.freshApp

  test("GET /health returns 200 with status ok") {
    buildApp.flatMap { app =>
      val req = Request[IO](Method.GET, uri("/health"))
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        assertEquals(body, """{"status":"ok"}""")
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 with created task with id 1") {
    buildApp.flatMap { app =>
      val req = Request[IO](Method.POST, uri("/tasks"))
        .withEntity("""{"title":"Test task"}""")
        .withHeader(Header.Raw("content-type", "application/json"))
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val body = resp.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(fail("Invalid JSON"))
        assertEquals((json \ "id").as[Long], Right(1L))
        assertEquals((json \ "title").as[String], Right("Test task"))
        assertEquals((json \ "done").as[Boolean], Right(false))
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    buildApp.flatMap { app =>
      // First create the task
      val createReq = Request[IO](Method.POST, uri("/tasks"))
        .withEntity("""{"title":"Task 1"}""")
        .withHeader(Header.Raw("content-type", "application/json"))
      app.run(createReq).use { _ =>
        // Then get it
        val getReq = Request[IO](Method.GET, uri("/tasks/1"))
        app.run(getReq).map { resp =>
          assertEquals(resp.status, Status.Ok)
          val body = resp.as[String].unsafeRunSync()
          val json = parse(body).getOrElse(fail("Invalid JSON"))
          assertEquals((json \ "id").as[Long], Right(1L))
          assertEquals((json \ "title").as[String], Right("Task 1"))
        }
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    buildApp.flatMap { app =>
      val req = Request[IO](Method.GET, uri("/tasks/999"))
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    buildApp.flatMap { app =>
      // Create a task first
      val createReq = Request[IO](Method.POST, uri("/tasks"))
        .withEntity("""{"title":"To delete"}""")
        .withHeader(Header.Raw("content-type", "application/json"))
      app.run(createReq).use { _ =>
        // Delete it
        val deleteReq = Request[IO](Method.DELETE, uri("/tasks/1"))
        app.run(deleteReq).flatMap { resp1 =>
          assertEquals(resp1.status, Status.NoContent)
          // Try to get it again
          val getReq = Request[IO](Method.GET, uri("/tasks/1"))
          app.run(getReq).map { resp2 =>
            assertEquals(resp2.status, Status.NotFound)
          }
        }
      }
    }.unsafeRunSync()
  }
}
```