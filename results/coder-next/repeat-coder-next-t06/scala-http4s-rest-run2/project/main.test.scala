//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package myapp

import munit.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import io.circe.syntax.*
import io.circe.generic.auto.*

class MainTest extends AsyncSuite:
  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      client
        .run(Request(GET, uri"/health"))
        .map { resp =>
          assertEquals(resp.status, Status.Ok)
          assertEquals(resp.as[String].unsafeRunSync(), """{"status":"ok"}""")
        }
    }.futureValue
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      client
        .run(Request(POST, uri"/tasks").withEntity("""{"title":"Test task"}"""))
        .map { resp =>
          assertEquals(resp.status, Status.Created)
          val task = resp.as[Task].unsafeRunSync()
          assertEquals(task.id, 1L)
          assertEquals(task.title, "Test task")
          assertEquals(task.done, false)
        }
    }.futureValue
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      for {
        _ <- client
          .run(Request(POST, uri"/tasks").withEntity("""{"title":"First task"}"""))
          .map(_.discard)
        resp <- client.run(Request(GET, uri"/tasks/1"))
      } yield {
        assertEquals(resp.status, Status.Ok)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "First task")
        assertEquals(task.done, false)
      }
    }.futureValue
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      client
        .run(Request(GET, uri"/tasks/999"))
        .map { resp =>
          assertEquals(resp.status, Status.NotFound)
        }
    }.futureValue
  }

  test("DELETE an existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      val client = Client.fromHttpApp(app)
      for {
        _ <- client
          .run(Request(POST, uri"/tasks").withEntity("""{"title":"To delete"}"""))
          .map(_.discard)
        deleteResp <- client.run(Request(DELETE, uri"/tasks/1"))
        getResp <- client.run(Request(GET, uri"/tasks/1"))
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }.futureValue
  }
