//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package com.example

import munit.FunSuite
import cats.effect.IO
import org.http4s._
import org.http4s.dsl._
import org.http4s.circe.CirceEntityEncoder._
import io.circe.syntax._
import org.http4s.Method._
import org.http4s.Request
import org.http4s.Status

class MainTest extends FunSuite {

  test("GET /health returns 200") {
    Main.freshApp.flatMap { app =>
      val request = Request[IO](GET, uri("/health"))
      app.run(request).map { response =>
        assertEquals(response.status, Status.Ok)
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.flatMap { app =>
      val request = Request[IO](POST, uri("/tasks"))
        .withEntity("""{"title":"Test task"}""").withContentType(org.http4s.ContentType.apply(org.http4s.MediaType.application.json))
      app.run(request).map { response =>
        assertEquals(response.status, Status.Created)
        val body = response.asJson.unsafeRunSync()
        assertEquals((body \ "id").as[Long].toOption.get, 1L)
        assertEquals((body \ "title").as[String].toOption.get, "Test task")
        assertEquals((body \ "done").as[Boolean].toOption.get, false)
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](POST, uri("/tasks"))
          .withEntity("""{"title":"First task"}""").withContentType(org.http4s.ContentType.apply(org.http4s.MediaType.application.json)))
        response <- app.run(Request[IO](GET, uri("/tasks/1")))
      } yield {
        assertEquals(response.status, Status.Ok)
        val body = response.asJson.unsafeRunSync()
        assertEquals((body \ "id").as[Long].toOption.get, 1L)
        assertEquals((body \ "title").as[String].toOption.get, "First task")
        assertEquals((body \ "done").as[Boolean].toOption.get, false)
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.flatMap { app =>
      app.run(Request[IO](GET, uri("/tasks/999"))).map { response =>
        assertEquals(response.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](POST, uri("/tasks"))
          .withEntity("""{"title":"To delete"}""").withContentType(org.http4s.ContentType.apply(org.http4s.MediaType.application.json)))
        deleteResponse <- app.run(Request[IO](DELETE, uri("/tasks/1")))
        getResponse <- app.run(Request[IO](GET, uri("/tasks/1")))
      } yield {
        assertEquals(deleteResponse.status, Status.NoContent)
        assertEquals(getResponse.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }
}
