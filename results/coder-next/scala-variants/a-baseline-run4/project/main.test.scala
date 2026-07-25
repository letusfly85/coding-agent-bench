//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

import munit.CatsEffectSuite
import org.http4s._
import org.http4s.dsl._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax._
import io.circe.parser._

import scala.util.Try

class MainTest extends CatsEffectSuite {
  private val app = Main.freshApp

  test("GET /health returns 200") {
    app.flatMap { app =>
      val req = Request[IO](Method.GET, uri"/health")
      req.run(app).map { resp =>
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        assertEquals(body, """{"status":"ok"}""")
      }
    }
  }

  test("POST /tasks returns 201 and id 1") {
    app.flatMap { app =>
      val body = """{"title":"Test task"}"""
      val req = Request[IO](Method.POST, uri"/tasks")
        .withEntity(body)
      req.run(app).map { resp =>
        assertEquals(resp.status, Status.Created)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Test task")
        assertEquals(task.done, false)
      }
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    app.flatMap { app =>
      val createReq = Request[IO](Method.POST, uri"/tasks")
        .withEntity("""{"title":"Another task"}""")
      for {
        _ <- createReq.run(app)
        getReq = Request[IO](Method.GET, uri"/tasks/1")
        resp <- getReq.run(app)
      } yield {
        assertEquals(resp.status, Status.Ok)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Another task")
        assertEquals(task.done, false)
      }
    }
  }

  test("GET /tasks/999 returns 404") {
    app.flatMap { app =>
      val req = Request[IO](Method.GET, uri"/tasks/999")
      req.run(app).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    app.flatMap { app =>
      val createReq = Request[IO](Method.POST, uri"/tasks")
        .withEntity("""{"title":"To delete"}""")
      for {
        _ <- createReq.run(app)
        deleteReq = Request[IO](Method.DELETE, uri"/tasks/1")
        deleteResp <- deleteReq.run(app)
        getReq = Request[IO](Method.GET, uri"/tasks/1")
        getResp <- getReq.run(app)
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }
  }
}
