//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import munit._
import cats.effect.IO
import org.http4s._
import org.http4s.dsl._
import org.http4s.circe.CirceEntityCodec.circeEntityCodec
import io.circe.syntax._
import io.circe.parser._

class MainTest extends FunSuite {
  private def buildApp = Main.freshApp

  test("GET /health returns 200 with status ok") {
    buildApp.flatMap { app =>
      val req = Request[IO](Method.GET, uri("/health"))
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        assertEquals(body, """{"status":"ok"}""")
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 with created task with id 1") {
    buildApp.flatMap { app =>
      val req = Request[IO](Method.POST, uri("/tasks"))
        .withEntity("""{"title":"Test task"}""")
        .withHeader(Header.Raw("content-type", "application/json"))
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val body = resp.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(fail("Invalid JSON"))
        assertEquals((json \ "id").as[Long], Right(1L))
        assertEquals((json \ "title").as[String], Right("Test task"))
        assertEquals((json \ "done").as[Boolean], Right(false))
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    buildApp.flatMap { app =>
      // First create the task
      val createReq = Request[IO](Method.POST, uri("/tasks"))
        .withEntity("""{"title":"Task 1"}""")
        .withHeader(Header.Raw("content-type", "application/json"))
      app.run(createReq).use { _ =>
        // Then get it
        val getReq = Request[IO](Method.GET, uri("/tasks/1"))
        app.run(getReq).map { resp =>
          assertEquals(resp.status, Status.Ok)
          val body = resp.as[String].unsafeRunSync()
          val json = parse(body).getOrElse(fail("Invalid JSON"))
          assertEquals((json \ "id").as[Long], Right(1L))
          assertEquals((json \ "title").as[String], Right("Task 1"))
        }
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    buildApp.flatMap { app =>
      val req = Request[IO](Method.GET, uri("/tasks/999"))
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    buildApp.flatMap { app =>
      // Create a task first
      val createReq = Request[IO](Method.POST, uri("/tasks"))
        .withEntity("""{"title":"To delete"}""")
        .withHeader(Header.Raw("content-type", "application/json"))
      app.run(createReq).use { _ =>
        // Delete it
        val deleteReq = Request[IO](Method.DELETE, uri("/tasks/1"))
        app.run(deleteReq).flatMap { resp1 =>
          assertEquals(resp1.status, Status.NoContent)
          // Try to get it again
          val getReq = Request[IO](Method.GET, uri("/tasks/1"))
          app.run(getReq).map { resp2 =>
            assertEquals(resp2.status, Status.NotFound)
          }
        }
      }
    }.unsafeRunSync()
  }
}
