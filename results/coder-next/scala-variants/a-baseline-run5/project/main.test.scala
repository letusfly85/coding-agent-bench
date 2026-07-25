//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

package example

import munit.*
import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.syntax.*
import io.circe.parser.*
import io.circe.Json

class MainTest extends FunSuite:
  test("GET /health returns 200") {
    Main.freshApp.flatMap(app =>
      app.run(Request[IO](Method.GET, uri"/health"))
    ).map { response =>
      assertEquals(response.status, Status.Ok)
      val body = response.as[String].unsafeRunSync()
      assertEquals(parse(body), Right(Json.obj("status" -> Json.fromString("ok"))))
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.flatMap(app =>
      app.run(
        Request[IO](
          method = Method.POST,
          uri = uri"/tasks",
          body = io.circe.syntax.JsonEncoder[Json].encodeJson(Json.obj("title" -> Json.fromString("Test task")), None)
        )
      )
    ).map { response =>
      assertEquals(response.status, Status.Created)
      val body = response.as[String].unsafeRunSync()
      assertEquals(parse(body), Right(Json.obj(
        "id" -> Json.fromLong(1L),
        "title" -> Json.fromString("Test task"),
        "done" -> Json.fromBoolean(false)
      )))
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.flatMap(app =>
      for {
        _ <- app.run(
          Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            body = io.circe.syntax.JsonEncoder[Json].encodeJson(Json.obj("title" -> Json.fromString("Test task")), None)
          )
        )
        response <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      } yield response
    ).map { response =>
      assertEquals(response.status, Status.Ok)
      val body = response.as[String].unsafeRunSync()
      assertEquals(parse(body), Right(Json.obj(
        "id" -> Json.fromLong(1L),
        "title" -> Json.fromString("Test task"),
        "done" -> Json.fromBoolean(false)
      )))
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.flatMap(app =>
      app.run(Request[IO](Method.GET, uri"/tasks/999"))
    ).map { response =>
      assertEquals(response.status, Status.NotFound)
    }.unsafeRunSync()
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    Main.freshApp.flatMap(app =>
      for {
        _ <- app.run(
          Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            body = io.circe.syntax.JsonEncoder[Json].encodeJson(Json.obj("title" -> Json.fromString("Delete me")), None)
          )
        )
        deleteResp <- app.run(Request[IO](Method.DELETE, uri"/tasks/1"))
        getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
      } yield (deleteResp, getResp)
    ).map { case (deleteResp, getResp) =>
      assertEquals(deleteResp.status, Status.NoContent)
      assertEquals(getResp.status, Status.NotFound)
    }.unsafeRunSync()
  }
