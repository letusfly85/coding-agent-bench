//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using dep org.scalameta::munit::1.0.4

import munit.*
import cats.effect.{IO, Ref}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.circe.parser._

class TaskApiTest extends FunSuite:
  test("GET /health returns 200"):
    TaskApi.freshApp.flatMap { app =>
      val req = Request[IO](Method.GET, uri"/health")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
      }
    }.unsafeRunSync()

  test("POST /tasks returns 201 and id 1"):
    TaskApi.freshApp.flatMap { app =>
      val req = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = io.circe.syntax.encode(CreateTaskReq("Test task")).orThrow
      )
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Test task")
        assertEquals(task.done, false)
      }
    }.unsafeRunSync()

  test("GET /tasks/1 after creation returns the task"):
    TaskApi.freshApp.flatMap { app =>
      for
        _ <- app.run(
          Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            body = io.circe.syntax.encode(CreateTaskReq("First task")).orThrow
          )
        )
        resp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      yield {
        assertEquals(resp.status, Status.Ok)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "First task")
        assertEquals(task.done, false)
      }
    }.unsafeRunSync()

  test("GET /tasks/999 returns 404"):
    TaskApi.freshApp.flatMap { app =>
      app.run(Request[IO](Method.GET, uri"/tasks/999")).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.unsafeRunSync()

  test("DELETE existing task returns 204 and subsequent GET returns 404"):
    TaskApi.freshApp.flatMap { app =>
      for
        _ <- app.run(
          Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            body = io.circe.syntax.encode(CreateTaskReq("To delete")).orThrow
          )
        )
        deleteResp <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
        getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }.unsafeRunSync()
