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
