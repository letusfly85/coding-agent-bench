import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.implicits._
import io.circe.generic.auto._
import io.circe.parser.decode
import fs2._

class ApiTests extends FunSuite {

  test("GET /health returns 200") {
    val app = Api.freshApp.unsafeRunSync()
    val req = Request[IO](GET, uri"/health")
    val resp = app.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val body = resp.bodyText.compile.string.unsafeRunSync()
    assertEquals(body, """{"status":"ok"}""")
  }

  test("POST /tasks returns 201 and id 1") {
    val app = Api.freshApp.unsafeRunSync()
    val req = Request[IO](Method.POST, uri"/tasks")
      .withEntity("""{"title":"Test"}""")
    val resp = app.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Created)
    val task = decode[Task](resp.bodyText.compile.string.unsafeRunSync())
    assertEquals(task.right.get.id, 1L)
  }

  test("GET /tasks/1 after creation returns the task") {
    val app = Api.freshApp.unsafeRunSync()
    app.run(Request[IO](Method.POST, uri"/tasks").withEntity("""{"title":"First"}""")).unsafeRunSync()

    val resp = app.run(Request[IO](GET, uri"/tasks/1")).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val task = decode[Task](resp.bodyText.compile.string.unsafeRunSync())
    assertEquals(task.right.get.title, "First")
  }

  test("GET /tasks/999 returns 404") {
    val app = Api.freshApp.unsafeRunSync()
    val resp = app.run(Request[IO](GET, uri"/tasks/999")).unsafeRunSync()
    assertEquals(resp.status, Status.NotFound)
  }

  test("DELETE existing task returns 204, subsequent GET returns 404") {
    val app = Api.freshApp.unsafeRunSync()
    app.run(Request[IO](Method.POST, uri"/tasks").withEntity("""{"title":"ToDel"}""")).unsafeRunSync()

    val delResp = app.run(Request[IO](Method.DELETE, uri"/tasks/1")).unsafeRunSync()
    assertEquals(delResp.status, Status.NoContent)

    val getResp = app.run(Request[IO](GET, uri"/tasks/1")).unsafeRunSync()
    assertEquals(getResp.status, Status.NotFound)
  }
}
