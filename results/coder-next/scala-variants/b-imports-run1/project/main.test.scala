//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import cats.effect.IO
import munit.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityCodec._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

class MainTest extends FunSuite:
  test("GET /health returns 200") {
    Main.freshApp.use { app =>
      val client = Client.fromHttpApp(app)
      client.run(Request[IO](Method.GET, uri"/health")).map { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.as[String].compile.string.blocking, """{"status":"ok"}""")
      }
    }.block()
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.use { app =>
      val client = Client.fromHttpApp(app)
      val body = """{"title":"Test task"}"""
      client.run(Request[IO](Method.POST, uri"/tasks")
        .withEntity(body)).map { resp =>
        assertEquals(resp.status, Status.Created)
        val task = resp.as[Task].compile.string.blocking
        val expected = Task(1L, "Test task", false)
        assertEquals(resp.as[Task].compile.string.blocking, expected.asJson.noSpaces)
      }
    }.block()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.use { app =>
      val client = Client.fromHttpApp(app)
      for
        _ <- client.run(Request[IO](Method.POST, uri"/tasks")
          .withEntity("""{"title":"First task"}"""))
        resp <- client.run(Request[IO](Method.GET, uri"/tasks/1"))
      yield
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.as[Task].compile.string.blocking, """{"id":1,"title":"First task","done":false}""")
    }.block()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.use { app =>
      val client = Client.fromHttpApp(app)
      client.run(Request[IO](Method.GET, uri"/tasks/999")).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.block()
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    Main.freshApp.use { app =>
      val client = Client.fromHttpApp(app)
      for
        _ <- client.run(Request[IO](Method.POST, uri"/tasks")
          .withEntity("""{"title":"To delete"}"""))
        deleteResp <- client.run(Request[IO](Method.DELETE, uri"/tasks/1"))
        getResp <- client.run(Request[IO](Method.GET, uri"/tasks/1"))
      yield
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
    }.block()
  }
