//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import munit._
import cats.effect.{IO, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import com.comcast.ip4s._

class MainTest extends FunSuite {
  private val task1 = Task(1L, "Test task", false)
  private val task2 = Task(2L, "Another task", true)

  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      val request = Request[IO](method = Method.GET, uri = uri"/health")
      app.run(request).map { response =>
        assertEquals(response.status, Status.Ok)
        val body = response.as[String].unsafeRunSync()
        assertEquals(body, """{"status":"ok"}""")
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      val request = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = task1.copy(id = 0L).asJson.noSpaces
      )
      app.run(request).map { response =>
        assertEquals(response.status, Status.Created)
        val body = response.as[String].unsafeRunSync()
        val parsed = parse(body).toOption.getOrElse(throw new RuntimeException("Invalid JSON"))
        assertEquals(parsed.hcursor.downField("id").as[Long].toOption, Some(1L))
        assertEquals(parsed.hcursor.downField("title").as[String].toOption, Some("Test task"))
        assertEquals(parsed.hcursor.downField("done").as[Boolean].toOption, Some(false))
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = task1.copy(id = 0L).asJson.noSpaces
      )
      app.run(createReq).flatMap { _ =>
        val getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
        app.run(getReq).map { response =>
          assertEquals(response.status, Status.Ok)
          val body = response.as[String].unsafeRunSync()
          val parsed = parse(body).toOption.getOrElse(throw new RuntimeException("Invalid JSON"))
          assertEquals(parsed.hcursor.downField("id").as[Long].toOption, Some(1L))
          assertEquals(parsed.hcursor.downField("title").as[String].toOption, Some("Test task"))
          assertEquals(parsed.hcursor.downField("done").as[Boolean].toOption, Some(false))
        }
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      val request = Request[IO](method = Method.GET, uri = uri"/tasks/999")
      app.run(request).map { response =>
        assertEquals(response.status, Status.NotFound)
      }
    }.unsafeRunSync()
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = task1.copy(id = 0L).asJson.noSpaces
      )
      app.run(createReq).flatMap { _ =>
        val deleteReq = Request[IO](method = Method.DELETE, uri = uri"/tasks/1")
        app.run(deleteReq).flatMap { deleteResponse =>
          assertEquals(deleteResponse.status, Status.NoContent)
          val getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
          app.run(getReq).map { getResponse =>
            assertEquals(getResponse.status, Status.NotFound)
          }
        }
      }
    }.unsafeRunSync()
  }
}
