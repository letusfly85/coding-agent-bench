//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4

package taskapi

import munit.*
import cats.effect.{IO, Resource}
import org.http4s.*
import org.http4s.dsl.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.parser.*
import io.circe.syntax.*

class TaskApiTest extends FunSuite:
  test("GET /health returns 200") {
    TaskApi.freshApp
      .flatMap(app => app.run(Request[IO](Method.GET, uri"/health")))
      .map { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.as[String].unsafeRunSync(), """{"status":"ok"}""")
      }
  }

  test("POST /tasks returns 201 and id 1") {
    TaskApi.freshApp
      .flatMap { app =>
        val req = Request[IO](Method.POST, uri"/tasks")
          .withEntity("""{"title":"First task"}""")
        app.run(req)
      }
      .map { resp =>
        assertEquals(resp.status, Status.Created)
        val json = parse(resp.as[String].unsafeRunSync()).leftMap(throw _).merge
        assertEquals((json \ "id").as[Long].toOption, Some(1L))
        assertEquals((json \ "title").as[String].toOption, Some("First task"))
        assertEquals((json \ "done").as[Boolean].toOption, Some(false))
      }
  }

  test("GET /tasks/1 after creation returns the task") {
    TaskApi.freshApp
      .flatMap { app =>
        for {
          _ <- app.run(
            Request[IO](Method.POST, uri"/tasks")
              .withEntity("""{"title":"First task"}""")
          )
          resp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
        } yield resp
      }
      .map { resp =>
        assertEquals(resp.status, Status.Ok)
        val json = parse(resp.as[String].unsafeRunSync()).leftMap(throw _).merge
        assertEquals((json \ "id").as[Long].toOption, Some(1L))
        assertEquals((json \ "title").as[String].toOption, Some("First task"))
        assertEquals((json \ "done").as[Boolean].toOption, Some(false))
      }
  }

  test("GET /tasks/999 returns 404") {
    TaskApi.freshApp
      .flatMap(app => app.run(Request[IO](Method.GET, uri"/tasks/999")))
      .map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    TaskApi.freshApp
      .flatMap { app =>
        for {
          _ <- app.run(
            Request[IO](Method.POST, uri"/tasks")
              .withEntity("""{"title":"Delete me"}""")
          )
          deleteResp <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
          getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
        } yield (deleteResp, getResp)
      }
      .map { case (deleteResp, getResp) =>
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
  }
