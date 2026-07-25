import munit.FunSuite
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.syntax._
import org.http4s.Uri._
import org.http4s.Method._
import org.http4s.Status._
import io.circe.parser._
import cats.effect.unsafe.implicits.global

class TaskApiTests extends FunSuite {

  test("GET /health returns 200") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val req = Request[IO](GET, uri"/health")
    val resp = app(req).unsafeRunSync()
    assertEquals(resp.status, Ok)
  }

  test("POST /tasks returns 201 and id 1") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val body = """{"title":"Buy milk"}"""
    val req = Request[IO](
      method = POST,
      uri = uri"/tasks",
      entity = body
    )
    val resp = app(req).unsafeRunSync()
    assertEquals(resp.status, Status.Created)
    val json = decode[Task](resp.bodyText.unsafeRunSync())
    assertEquals(json.right.get.id, 1L)
    assertEquals(json.right.get.title, "Buy milk")
  }

  test("GET /tasks/1 after creation returns the task") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val createReq = Request[IO](
      method = POST,
      uri = uri"/tasks",
      entity = """{"title":"Test task"}"""
    )
    app(createReq).unsafeRunSync()

    val getReq = Request[IO](GET, uri"/tasks/1")
    val getResp = app(getReq).unsafeRunSync()
    assertEquals(getResp.status, Ok)
    val task = decode[Task](getResp.bodyText.unsafeRunSync()).right.get
    assertEquals(task.id, 1L)
    assertEquals(task.title, "Test task")
  }

  test("GET /tasks/999 returns 404") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val req = Request[IO](GET, uri"/tasks/999")
    val resp = app(req).unsafeRunSync()
    assertEquals(resp.status, NotFound)
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    val app = TaskApi.freshApp.unsafeRunSync()
    val createReq = Request[IO](
      method = POST,
      uri = uri"/tasks",
      entity = """{"title":"To delete"}"""
    )
    app(createReq).unsafeRunSync()

    val deleteReq = Request[IO](DELETE, uri"/tasks/1")
    val deleteResp = app(deleteReq).unsafeRunSync()
    assertEquals(deleteResp.status, NoContent)

    val getReq = Request[IO](GET, uri"/tasks/1")
    val getResp = app(getReq).unsafeRunSync()
    assertEquals(getResp.status, NotFound)
  }
}
