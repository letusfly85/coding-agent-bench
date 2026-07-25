//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using test.dep org.scalameta::munit::1.0.4

import munit.*
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.generic.auto._
import io.circe.syntax._

class TaskApiSpec extends FunSuite:
  test("GET /health returns 200") {
    Api.freshApp
      .flatMap(_.run(Request[IO](Method.GET, uri"/health")))
      .map { response =>
        assertEquals(response.status, Status.Ok)
        assertEquals(response.body.compile.string.unsafeRunSync(), """{"status":"ok"}""")
      }
      .unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Api.freshApp
      .flatMap { app =>
        val req = Request[IO](Method.POST, uri"/tasks")
          .withEntity(CreateTaskReq("Test task"))
        app.run(req).map { response =>
          assertEquals(response.status, Status.Created)
          response.as[Task].map { task =>
            assertEquals(task.id, 1L)
            assertEquals(task.title, "Test task")
            assertEquals(task.done, false)
          }
        }
      }
      .unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Api.freshApp
      .flatMap { app =>
        for {
          _ <- app.run(Request[IO](Method.POST, uri"/tasks").withEntity(CreateTaskReq("First task")))
          response <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
        } yield response
      }
      .map { response =>
        assertEquals(response.status, Status.Ok)
        response.as[Task].map { task =>
          assertEquals(task.id, 1L)
          assertEquals(task.title, "First task")
          assertEquals(task.done, false)
        }
      }
      .unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Api.freshApp
      .flatMap(_.run(Request[IO](Method.GET, uri"/tasks/999")))
      .map { response =>
        assertEquals(response.status, Status.NotFound)
      }
      .unsafeRunSync()
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    Api.freshApp
      .flatMap { app =>
        for {
          _ <- app.run(Request[IO](Method.POST, uri"/tasks").withEntity(CreateTaskReq("To delete")))
          deleteResponse <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
          getResponse <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
        } yield (deleteResponse, getResponse)
      }
      .map { case (deleteResponse, getResponse) =>
        assertEquals(deleteResponse.status, Status.NoContent)
        assertEquals(getResponse.status, Status.NotFound)
      }
      .unsafeRunSync()
  }
