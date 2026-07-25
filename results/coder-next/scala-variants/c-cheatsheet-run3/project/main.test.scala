//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package taskapi

import munit.*
import cats.effect.*
import cats.effect.std.Console
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.syntax.*
import io.circe.parser.*

class TaskApiTest extends munit.CatsEffectSuite {

  private val taskApi = Main.freshApp

  test("GET /health returns 200") {
    for {
      app <- taskApi
      request = Request[IO](Method.GET, uri"/health")
      response <- app.run(request)
      body <- response.as[String]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body, """{"status":"ok"}""")
    }
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      app <- taskApi
      request = Request[IO](Method.POST, uri"/tasks")
        .withEntity("""{"title":"Test task"}""")
        .withContentType(org.http4s.ContentType(`application/json`))
      response <- app.run(request)
      body <- response.as[String]
      json <- IO.fromEither(parse(body))
    } yield {
      assertEquals(response.status, Status.Created)
      assertEquals((json \ "id").as[Long], 1L)
      assertEquals((json \ "title").as[String], "Test task")
      assertEquals((json \ "done").as[Boolean], false)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      app <- taskApi
      // First create a task
      _ <- app.run(
        Request[IO](Method.POST, uri"/tasks")
          .withEntity("""{"title":"First task"}""")
          .withContentType(org.http4s.ContentType(`application/json`))
      )
      // Then fetch it
      response <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      body <- response.as[String]
      json <- IO.fromEither(parse(body))
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals((json \ "id").as[Long], 1L)
      assertEquals((json \ "title").as[String], "First task")
      assertEquals((json \ "done").as[Boolean], false)
    }
  }

  test("GET /tasks/999 returns 404") {
    for {
      app <- taskApi
      request = Request[IO](Method.GET, uri"/tasks/999")
      response <- app.run(request)
    } yield {
      assertEquals(response.status, Status.NotFound)
    }
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    for {
      app <- taskApi
      // Create a task first
      _ <- app.run(
        Request[IO](Method.POST, uri"/tasks")
          .withEntity("""{"title":"To delete"}""")
          .withContentType(org.http4s.ContentType(`application/json`))
      )
      // Delete it
      deleteResponse <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
      // Get it again
      getResponse <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      assertEquals(getResponse.status, Status.NotFound)
    }
  }
}
