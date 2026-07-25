import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.syntax.all._
import io.circe.parser._
import io.circe.syntax._
import Api._
import scala.util.Try

class TaskApiSuite extends FunSuite {
  var app: HttpApp[IO] = _

  override def beforeEach(context: BeforeEach): Unit = {
    app = freshApp.unsafeRunSync()
  }

  private def runRequest(req: Request[IO]): IO[Response[IO]] =
    app(req)

  test("GET /health returns 200") {
    val req = Request[IO](Method.GET, uri"/health")
    val resp = runRequest(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val body = decode[Map[String, String]](resp.bodyText.unsafeRunSync()).toTry.get
    assertEquals(body("status"), "ok")
  }

  test("POST /tasks returns 201 and id 1") {
    val json = TaskCreateRequest("First task").asJson.noSpaces
    val req = Request[IO](Method.POST, uri"/tasks").withEntity(json)
    val resp = runRequest(req).unsafeRunSync()
    assertEquals(resp.status, Status.Created)
    val task = decode[Task](resp.bodyText.unsafeRunSync()).toTry.get
    assertEquals(task.id, 1L)
    assertEquals(task.title, "First task")
    assertEquals(task.done, false)
  }

  test("GET /tasks/1 after creation returns the task") {
    val json = TaskCreateRequest("Get me").asJson.noSpaces
    val createReq = Request[IO](Method.POST, uri"/tasks").withEntity(json)
    runRequest(createReq).unsafeRunSync()

    val getReq = Request[IO](Method.GET, uri"/tasks/1")
    val resp = runRequest(getReq).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val task = decode[Task](resp.bodyText.unsafeRunSync()).toTry.get
    assertEquals(task.title, "Get me")
  }

  test("GET /tasks/999 returns 404") {
    val req = Request[IO](Method.GET, uri"/tasks/999")
    val resp = runRequest(req).unsafeRunSync()
    assertEquals(resp.status, Status.NotFound)
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    val json = TaskCreateRequest("Delete me").asJson.noSpaces
    val createReq = Request[IO](Method.POST, uri"/tasks").withEntity(json)
    val createResp = runRequest(createReq).unsafeRunSync()
    val task = decode[Task](createResp.bodyText.unsafeRunSync()).toTry.get

    val deleteReq = Request[IO](Method.DELETE, uri"/tasks/${task.id}")
    val deleteResp = runRequest(deleteReq).unsafeRunSync()
    assertEquals(deleteResp.status, Status.NoContent)

    val getReq = Request[IO](Method.GET, uri"/tasks/${task.id}")
    val getResp = runRequest(getReq).unsafeRunSync()
    assertEquals(getResp.status, Status.NotFound)
  }
}
