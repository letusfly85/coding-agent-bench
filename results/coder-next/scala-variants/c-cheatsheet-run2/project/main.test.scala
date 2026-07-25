//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import munit.CatsEffectSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax._
import io.circe.parser._

class MainTest extends CatsEffectSuite {
  private val baseUri = Uri(path = "/")

  test("GET /health returns 200") {
    for {
      app <- Main.freshApp
      req = Request[IO](Method.GET, baseUri / "health")
      resp <- app.run(req)
      body <- resp.as[String]
    } yield {
      assertEquals(resp.status, Status.Ok)
      assertEquals(body, """{"status":"ok"}""")
    }
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      app <- Main.freshApp
      body = """{"title":"Test task"}"""
      req = Request[IO](
        method = Method.POST,
        uri = baseUri / "tasks",
        body = io.circe.syntax.Json.fromString(body).asJson.noSpaces
      ).withContentType(ContentTypes.ApplicationJson)
      resp <- app.run(req)
      json <- resp.as[String].map(parse(_).toOption.get)
    } yield {
      assertEquals(resp.status, Status.Created)
      assertEquals((json \ "id").as[Long].toOption.get, 1L)
      assertEquals((json \ "title").as[String].toOption.get, "Test task")
      assertEquals((json \ "done").as[Boolean].toOption.get, false)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      app <- Main.freshApp
      createReq = Request[IO](
        method = Method.POST,
        uri = baseUri / "tasks",
        body = io.circe.syntax.Json.fromString("""{"title":"First task"}""").asJson.noSpaces
      ).withContentType(ContentTypes.ApplicationJson)
      createResp <- app.run(createReq)
      getReq = Request[IO](Method.GET, baseUri / "tasks" / "1")
      getResp <- app.run(getReq)
      json <- getResp.as[String].map(parse(_).toOption.get)
    } yield {
      assertEquals(getResp.status, Status.Ok)
      assertEquals((json \ "id").as[Long].toOption.get, 1L)
      assertEquals((json \ "title").as[String].toOption.get, "First task")
      assertEquals((json \ "done").as[Boolean].toOption.get, false)
    }
  }

  test("GET /tasks/999 returns 404") {
    for {
      app <- Main.freshApp
      req = Request[IO](Method.GET, baseUri / "tasks" / "999")
      resp <- app.run(req)
    } yield assertEquals(resp.status, Status.NotFound)
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    for {
      app <- Main.freshApp
      // Create a task first
      createReq = Request[IO](
        method = Method.POST,
        uri = baseUri / "tasks",
        body = io.circe.syntax.Json.fromString("""{"title":"To delete"}""").asJson.noSpaces
      ).withContentType(ContentTypes.ApplicationJson)
      _ <- app.run(createReq)
      // Delete it
      deleteReq = Request[IO](Method.DELETE, baseUri / "tasks" / "1")
      deleteResp <- app.run(deleteReq)
      // Try to get it again
      getReq = Request[IO](Method.GET, baseUri / "tasks" / "1")
      getResp <- app.run(getReq)
    } yield {
      assertEquals(deleteResp.status, Status.NoContent)
      assertEquals(getResp.status, Status.NotFound)
    }
  }
}
