//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import org.http4s.{Request, Uri}
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax._
import io.circe.parser._

class ApiTest extends CatsEffectSuite:
  val app = Api.freshApp

  test("GET /health returns 200") {
    app.use { app =>
      Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/health"))
        .pure[IO]
        .flatMap(req => app.run(req).map(resp => assertEquals(resp.status, Status.Ok)))
    }
  }

  test("POST /tasks returns 201 and id 1") {
    app.use { app =>
      val req = Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        body = """{"title":"Test task"}""".asJson.noSpaces
      )
      req.pure[IO].flatMap(req => app.run(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val body = resp.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(throw new Exception("Invalid JSON"))
        assertEquals((json \ "id").as[Long], Right(1L))
      })
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    app.use { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        body = """{"title":"Test task"}""".asJson.noSpaces
      )
      for {
        _ <- createReq.pure[IO].flatMap(createReq => app.run(createReq))
        getReq = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/tasks/1"))
        resp <- getReq.pure[IO].flatMap(req => app.run(req))
      } yield {
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(throw new Exception("Invalid JSON"))
        assertEquals((json \ "id").as[Long], Right(1L))
        assertEquals((json \ "title").as[String], Right("Test task"))
        assertEquals((json \ "done").as[Boolean], Right(false))
      }
    }
  }

  test("GET /tasks/999 returns 404") {
    app.use { app =>
      Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/tasks/999"))
        .pure[IO]
        .flatMap(req => app.run(req).map(resp => assertEquals(resp.status, Status.NotFound)))
    }
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    app.use { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        body = """{"title":"Test task"}""".asJson.noSpaces
      )
      for {
        _ <- createReq.pure[IO].flatMap(createReq => app.run(createReq))
        deleteReq = Request[IO](method = Method.DELETE, uri = Uri.unsafeFromString("/tasks/1"))
        deleteResp <- deleteReq.pure[IO].flatMap(req => app.run(req))
        getReq = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/tasks/1"))
        getResp <- getReq.pure[IO].flatMap(req => app.run(req))
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }
  }
