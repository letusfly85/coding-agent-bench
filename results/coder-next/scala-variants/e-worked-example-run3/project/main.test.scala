//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using dep org.scalameta::munit::1.0.4

import munit.*
import cats.effect.{IO, Ref}
import org.http4s.*
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax.*
import io.circe.parser._

import TaskApi._

class TaskApiTest extends FunSuite:

  test("GET /health returns 200") {
    val test = for
      app <- TaskApi.freshApp
      req = Request[IO](Method.GET, uri"/health")
      resp <- app.run(req)
    yield resp.status.code

    test.assertEquals(200)
  }

  test("POST /tasks returns 201 and id 1") {
    val test = for
      app <- TaskApi.freshApp
      req = Request[IO](Method.POST, uri"/tasks")
        .withEntity(CreateTaskReq("Test task"))
      resp <- app.run(req)
      body <- resp.as[Task]
    yield (resp.status.code, body.id)

    test.assertEquals((201, 1L))
  }

  test("GET /tasks/1 after creation returns the task") {
    val test = for
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](Method.POST, uri"/tasks").withEntity(CreateTaskReq("Test task")))
      req = Request[IO](Method.GET, uri"/tasks/1")
      resp <- app.run(req)
      body <- resp.as[Task]
    yield (resp.status.code, body.id, body.title, body.done)

    test.assertEquals((200, 1L, "Test task", false))
  }

  test("GET /tasks/999 returns 404") {
    val test = for
      app <- TaskApi.freshApp
      req = Request[IO](Method.GET, uri"/tasks/999")
      resp <- app.run(req)
    yield resp.status.code

    test.assertEquals(404)
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    val test = for
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](Method.POST, uri"/tasks").withEntity(CreateTaskReq("To delete")))
      deleteResp <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
      getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
    yield (deleteResp.status.code, getResp.status.code)

    test.assertEquals((204, 404))
  }
