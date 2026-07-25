//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import munit._
import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.syntax._
import io.circe.parser._
import io.circe.generic.auto._

class MainTest extends FunSuite {
  import Main.freshApp

  test("GET /health returns 200") {
    freshApp.flatMap(app => app.run(Request[IO](Method.GET, uri"/health")))
      .map(response => assertEquals(response.status, Status.Ok))
  }

  test("POST /tasks returns 201 and id 1") {
    freshApp.flatMap { app =>
      val request = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = io.circe.syntax.encode(Task(0L, "Test task", false)).orThrow.asJson.noSpaces
      )
      app.run(request)
    }.map { response =>
      assertEquals(response.status, Status.Created)
      val body = response.as[String].unsafeRunSync()
      val json = parse(body).getOrElse(io.circe.Json.Null)
      assertEquals((json \ "id").as[Long].toOption.get, 1L)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](Method.POST, uri"/tasks", body = """{"title":"First task"}"""))
        response <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      } yield response
    }.map { response =>
      assertEquals(response.status, Status.Ok)
      val body = response.as[String].unsafeRunSync()
      val json = parse(body).getOrElse(io.circe.Json.Null)
      assertEquals((json \ "id").as[Long].toOption.get, 1L)
      assertEquals((json \ "title").as[String].toOption.get, "First task")
      assertEquals((json \ "done").as[Boolean].toOption.get, false)
    }
  }

  test("GET /tasks/999 returns 404") {
    freshApp.flatMap(app => app.run(Request[IO](Method.GET, uri"/tasks/999")))
      .map(response => assertEquals(response.status, Status.NotFound))
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    freshApp.flatMap { app =>
      for {
        _ <- app.run(Request[IO](Method.POST, uri"/tasks", body = """{"title":"To delete"}"""))
        deleteResp <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
        getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      } yield (deleteResp, getResp)
    }.map { case (deleteResp, getResp) =>
      assertEquals(deleteResp.status, Status.NoContent)
      assertEquals(getResp.status, Status.NotFound)
    }
  }
}
