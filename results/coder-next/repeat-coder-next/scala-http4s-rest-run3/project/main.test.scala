//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import munit.*
import org.http4s.*
import org.http4s.circe.*
import io.circe.syntax.*
import cats.effect.IO

class MainTest extends FunSuite:
  test("GET /health returns 200") {
    Main.freshApp.flatMap(_.run(Request[IO](method = Method.GET, uri = Uri(path = "/health"))))
      .map { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.asJson.map(_.toString).unsafeRunSync(), """{"status":"ok"}""")
      }
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.flatMap { app =>
      val body = """{"title":"Test task"}"""
      app.run(Request[IO](
        method = Method.POST,
        uri = Uri(path = "/tasks"),
        body = EntityEncoder[IO, String].encode(body)
      ))
    }.map { resp =>
      assertEquals(resp.status, Status.Created)
      val json = resp.asJson.unsafeRunSync()
      assertEquals((json \ "id").as[Long], Right(1L))
      assertEquals((json \ "title").as[String], Right("Test task"))
      assertEquals((json \ "done").as[Boolean], Right(false))
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](method = Method.POST, uri = Uri(path = "/tasks"), body = "{'title':'First'}".asJson.noSpaces))
        resp <- app.run(Request[IO](method = Method.GET, uri = Uri(path = "/tasks/1")))
      } yield resp
    }.map { resp =>
      assertEquals(resp.status, Status.Ok)
      val json = resp.asJson.unsafeRunSync()
      assertEquals((json \ "id").as[Long], Right(1L))
      assertEquals((json \ "title").as[String], Right("First"))
    }
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.flatMap(_.run(Request[IO](method = Method.GET, uri = Uri(path = "/tasks/999"))))
      .map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
  }

  test("DELETE existing task returns 204, subsequent GET returns 404") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](method = Method.POST, uri = Uri(path = "/tasks"), body = "{'title':'Delete me'}".asJson.noSpaces))
        deleteResp <- app.run(Request[IO](method = Method.DELETE, uri = Uri(path = "/tasks/1")))
        getResp <- app.run(Request[IO](method = Method.GET, uri = Uri(path = "/tasks/1")))
      } yield (deleteResp, getResp)
    }.map { case (deleteResp, getResp) =>
      assertEquals(deleteResp.status, Status.NoContent)
      assertEquals(getResp.status, Status.NotFound)
    }
  }
