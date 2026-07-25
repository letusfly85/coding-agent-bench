//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import munit.*
import cats.effect.{IO, Ref}
import org.http4s.*
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec.*
import io.circe.syntax.*
import io.circe.parser._
import org.http4s.Method.*
import org.http4s.Request
import cats.effect.unsafe.implicits.global

class TaskApiTest extends FunSuite:

  test("GET /health returns 200") {
    for
      app <- TaskApi.freshApp
      request = Request[IO](method = GET, uri = uri"/health")
      response <- app.run(request)
    yield assertEquals(response.status, Status.Ok)
  }

  test("POST /tasks returns 201 and id 1") {
    for
      app <- TaskApi.freshApp
      request = Request[IO](
        method = POST,
        uri = uri"/tasks",
        body = """{"title":"First task"}""".asJson.noSpaces
      )
      response <- app.run(request)
      body <- response.as[String]
      json <- IO.fromEither(parse(body))
      id = json("id").as[Long].toOption.get
    yield {
      assertEquals(response.status, Status.Created)
      assertEquals(id, 1L)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](method = POST, uri = uri"/tasks", body = """{"title":"Task 1"}""".asJson.noSpaces))
      response <- app.run(Request[IO](method = GET, uri = uri"/tasks/1"))
      body <- response.as[String]
      json <- IO.fromEither(parse(body))
      title = json("title").as[String].toOption.get
    yield assertEquals(title, "Task 1")
  }

  test("GET /tasks/999 returns 404") {
    for
      app <- TaskApi.freshApp
      request = Request[IO](method = GET, uri = uri"/tasks/999")
      response <- app.run(request)
    yield assertEquals(response.status, Status.NotFound)
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    for
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](method = POST, uri = uri"/tasks", body = """{"title":"Task to delete"}""".asJson.noSpaces))
      deleteResponse <- app.run(Request[IO](method = DELETE, uri = uri"/tasks/1"))
      getResponse <- app.run(Request[IO](method = GET, uri = uri"/tasks/1"))
    yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      assertEquals(getResponse.status, Status.NotFound)
    }
  }
