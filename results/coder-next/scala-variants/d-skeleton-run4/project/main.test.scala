//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using dep org.scalameta::munit::1.0.4

import munit.CatsEffectSuite
import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.parser._
import io.circe.syntax._

class MainSuite extends CatsEffectSuite:
  
  test("GET /health returns 200") {
    for
      app <- Api.freshApp
      request = Request[IO](Method.GET, uri"/health")
      response <- app.run(request)
    yield assertEquals(response.status, Status.Ok)
  }
  
  test("POST /tasks returns 201 and id 1") {
    for
      app <- Api.freshApp
      request = Request[IO](Method.POST, uri"/tasks")
        .withEntity("""{"title":"Test task"}""")
      response <- app.run(request)
      body <- response.as[String]
      json <- IO.fromEither(parse(body).left.map(new Exception(_)))
      id = (json \ "id").as[Long]
    yield {
      assertEquals(response.status, Status.Created)
      assertEquals(id, 1L)
    }
  }
  
  test("GET /tasks/1 after creation returns the task") {
    for
      app <- Api.freshApp
      _ <- app.run(Request[IO](Method.POST, uri"/tasks").withEntity("""{"title":"Test task"}"""))
      request = Request[IO](Method.GET, uri"/tasks/1")
      response <- app.run(request)
      body <- response.as[String]
      json <- IO.fromEither(parse(body).left.map(new Exception(_)))
      id = (json \ "id").as[Long]
      title = (json \ "title").as[String]
      done = (json \ "done").as[Boolean]
    yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(id, 1L)
      assertEquals(title, "Test task")
      assertEquals(done, false)
    }
  }
  
  test("GET /tasks/999 returns 404") {
    for
      app <- Api.freshApp
      request = Request[IO](Method.GET, uri"/tasks/999")
      response <- app.run(request)
    yield assertEquals(response.status, Status.NotFound)
  }
  
  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    for
      app <- Api.freshApp
      _ <- app.run(Request[IO](Method.POST, uri"/tasks").withEntity("""{"title":"Test task"}"""))
      deleteRequest = Request[IO](Method.DELETE, uri"/tasks/1")
      deleteResponse <- app.run(deleteRequest)
      getRequest = Request[IO](Method.GET, uri"/tasks/1")
      getResponse <- app.run(getRequest)
    yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      assertEquals(getResponse.status, Status.NotFound)
    }
  }
