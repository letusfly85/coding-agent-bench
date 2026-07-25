//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import io.circe.syntax._
import io.circe.parser._

import scala.concurrent.duration._

class MainTest extends CatsEffectSuite {
  private val dsl = Http4sDsl[IO]
  import dsl._

  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      val req = Request[IO](Method.GET, uri"/health")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        assert(body.contains("\"status\":\"ok\""))
      }
    }.flatten
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      val body = """{"title":"First task"}"""
      val req = Request[IO](Method.POST, uri"/tasks")
        .withEntity(body)
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val json = parse(resp.as[String].unsafeRunSync()).toOption.get
        assertEquals((json \ "id").as[Long], 1L)
        assertEquals((json \ "title").as[String], "First task")
        assertEquals((json \ "done").as[Boolean], false)
      }
    }.flatten
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](Method.POST, uri"/tasks")
        .withEntity("""{"title":"Task to get"}""")
      val getReq = Request[IO](Method.GET, uri"/tasks/1")

      for {
        _ <- app.run(createReq).void
        resp <- app.run(getReq)
      } yield {
        assertEquals(resp.status, Status.Ok)
        val json = parse(resp.as[String].unsafeRunSync()).toOption.get
        assertEquals((json \ "id").as[Long], 1L)
        assertEquals((json \ "title").as[String], "Task to get")
      }
    }.flatten
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      val req = Request[IO](Method.GET, uri"/tasks/999")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.flatten
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](Method.POST, uri"/tasks")
        .withEntity("""{"title":"To delete"}""")
      val deleteReq = Request[IO](Method.DELETE, uri"/tasks/1")
      val getReq = Request[IO](Method.GET, uri"/tasks/1")

      for {
        _ <- app.run(createReq).void
        deleteResp <- app.run(deleteReq)
        getResp <- app.run(getReq)
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }.flatten
  }
}
