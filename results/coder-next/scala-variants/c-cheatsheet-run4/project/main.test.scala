//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import munit.*
import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.ember.server.EmberServerBuilder
import io.circe.syntax.*
import io.circe.parser.*
import com.comcast.ip4s.*

class MainTest extends FunSuite:

  test("GET /health returns 200") {
    Main.freshApp.flatMap { app =>
      val req = Request[IO](Method.GET, uri"/health")
      app(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        assertEquals(body, """{"status":"ok"}""")
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.flatMap { app =>
      val task = Task(0L, "Test task", false)
      val req = Request[IO](Method.POST, uri"/tasks")
        .withEntity(task.copy(id = 0L))
      app(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val body = resp.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertEquals((json \ "id").as[Long], Right(1L))
        assertEquals((json \ "title").as[String], Right("Test task"))
        assertEquals((json \ "done").as[Boolean], Right(false))
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app(
          Request[IO](Method.POST, uri"/tasks")
            .withEntity(Task(0L, "First task", false))
        )
        resp <- app(Request[IO](Method.GET, uri"/tasks/1"))
      } yield {
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertEquals((json \ "id").as[Long], Right(1L))
        assertEquals((json \ "title").as[String], Right("First task"))
        assertEquals((json \ "done").as[Boolean], Right(false))
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.flatMap { app =>
      app(Request[IO](Method.GET, uri"/tasks/999")).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.flatMap { app =>
      for {
        _ <- app(
          Request[IO](Method.POST, uri"/tasks")
            .withEntity(Task(0L, "Delete me", true))
        )
        deleteResp <- app(Request[IO](Method.DELETE, uri"/tasks/1"))
        getResp <- app(Request[IO](Method.GET, uri"/tasks/1"))
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }
