//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10

package example

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.Method._
import org.http4s.Request
import org.http4s.Uri.Path
import org.http4s.syntax.all._

class MainTest extends CatsEffectSuite {

  def makeRequest(app: HttpApp[IO], method: Method, path: Path, body: Option[String] = None): IO[Response[IO]] = {
    val req = method(path) ++ body.fold(Request[IO]()) { b =>
      Request[IO]().withEntity(b)
    }
    app.run(req)
  }

  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      makeRequest(app, GET, Path.root).map { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.as[String].unsafeRunSync(), """{"status":"ok"}""")
      }
    }.flatten
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      makeRequest(app, POST, Path.root, Some("""{"title":"Test task"}""")).map { resp =>
        assertEquals(resp.status, Status.Created)
        val body = resp.as[String].unsafeRunSync()
        val json = io.circe.parser.parse(body).toOption.get
        assertEquals((json \ "id").as[Long], 1L)
        assertEquals((json \ "title").as[String], "Test task")
        assertEquals((json \ "done").as[Boolean], false)
      }
    }.flatten
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      for {
        _ <- makeRequest(app, POST, Path.root, Some("""{"title":"First task"}""")).void
        resp <- makeRequest(app, GET, Path.root / 1)
      } yield {
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        val json = io.circe.parser.parse(body).toOption.get
        assertEquals((json \ "id").as[Long], 1L)
        assertEquals((json \ "title").as[String], "First task")
        assertEquals((json \ "done").as[Boolean], false)
      }
    }.flatten
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      makeRequest(app, GET, Path.root / 999).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.flatten
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      for {
        _ <- makeRequest(app, POST, Path.root, Some("""{"title":"To delete"}""")).void
        deleteResp <- makeRequest(app, DELETE, Path.root / 1)
        getResp <- makeRequest(app, GET, Path.root / 1)
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }.flatten
  }
}
