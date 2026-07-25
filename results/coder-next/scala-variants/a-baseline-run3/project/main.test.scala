//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package myapp

import munit.*
import cats.effect.IO
import org.http4s.*
import org.http4s.dsl._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import io.circe.parser._
import io.circe.syntax._

class MainTest extends FunSuite:

  test("GET /health returns 200") {
    Main.freshApp.flatMap(app =>
      Request[IO](Method.GET, uri("/health")).run(app).map { response =>
        assertEquals(response.status, Status.Ok)
        val body = response.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertEquals(json.hcursor.downField("status").as[String], Right("ok"))
      }
    ).unsafeRunSync()
  }

  test("POST /tasks returns 201 with id 1") {
    Main.freshApp.flatMap(app =>
      Request[IO](Method.POST, uri("/tasks"))
        .withEntity("""{"title":"Test task"}""")
        .run(app).map { response =>
          assertEquals(response.status, Status.Created)
          val body = response.as[String].unsafeRunSync()
          val json = parse(body).getOrElse(Json.Null)
          assertEquals(json.hcursor.downField("id").as[Long], Right(1L))
          assertEquals(json.hcursor.downField("title").as[String], Right("Test task"))
          assertEquals(json.hcursor.downField("done").as[Boolean], Right(false))
        }
    ).unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.flatMap(app =>
      for {
        _ <- Request[IO](Method.POST, uri("/tasks"))
          .withEntity("""{"title":"First task"}""")
          .run(app)
        response <- Request[IO](Method.GET, uri("/tasks/1")).run(app)
      } yield {
        assertEquals(response.status, Status.Ok)
        val body = response.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertEquals(json.hcursor.downField("id").as[Long], Right(1L))
        assertEquals(json.hcursor.downField("title").as[String], Right("First task"))
        assertEquals(json.hcursor.downField("done").as[Boolean], Right(false))
      }
    ).unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.flatMap(app =>
      Request[IO](Method.GET, uri("/tasks/999")).run(app).map { response =>
        assertEquals(response.status, Status.NotFound)
        val body = response.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertTrue(json.hcursor.downField("error").as[String].isRight)
      }
    ).unsafeRunSync()
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.flatMap(app =>
      for {
        _ <- Request[IO](Method.POST, uri("/tasks"))
          .withEntity("""{"title":"To delete"}""")
          .run(app)
        deleteResponse <- Request[IO](Method.DELETE, uri("/tasks/1")).run(app)
        getResponse <- Request[IO](Method.GET, uri("/tasks/1")).run(app)
      } yield {
        assertEquals(deleteResponse.status, Status.NoContent)
        assertEquals(getResponse.status, Status.NotFound)
      }
    ).unsafeRunSync()
  }
