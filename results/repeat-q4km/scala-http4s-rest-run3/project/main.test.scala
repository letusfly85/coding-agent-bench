import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s._
import org.http4s.Method._
import org.http4s.Status._
import org.http4s.circe._
import org.http4s.syntax.all._
import io.circe.generic.auto._

class TaskApiSuite extends FunSuite {
  def createApp(): IO[HttpApp[IO]] = App.freshApp

  test("GET /health returns 200") {
    val status = createApp().flatMap { app =>
      val req = Request[IO](method = GET, uri = uri"/health")
      app(req).map(_.status)
    }.unsafeRunSync()
    assertEquals(status, Ok)
  }

  test("POST /tasks returns 201 and id 1") {
    val (status, id) = createApp().flatMap { app =>
      val req = Request[IO](
        method = POST,
        uri = uri"/tasks",
        entity = """{"title":"First task"}""",
        headers = Headers(Header("Content-Type", "application/json"))
      )
      app(req).flatMap { resp =>
        resp.as[Task].map(t => (resp.status, t.id))
      }
    }.unsafeRunSync()
    assertEquals(status, Created)
    assertEquals(id, 1L)
  }

  test("GET /tasks/1 after creation returns the task") {
    val title = createApp().flatMap { app =>
      val createReq = Request[IO](
        method = POST,
        uri = uri"/tasks",
        entity = """{"title":"Test"}""",
        headers = Headers(Header("Content-Type", "application/json"))
      )
      for {
        _ <- app(createReq)
        getReq = Request[IO](method = GET, uri = uri"/tasks/1")
        task <- app(getReq).flatMap(_.as[Task])
      } yield task.title
    }.unsafeRunSync()
    assertEquals(title, "Test")
  }

  test("GET /tasks/999 returns 404") {
    val status = createApp().flatMap { app =>
      val req = Request[IO](method = GET, uri = uri"/tasks/999")
      app(req).map(_.status)
    }.unsafeRunSync()
    assertEquals(status, NotFound)
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    val (deleteStatus, getStatus) = createApp().flatMap { app =>
      val createReq = Request[IO](
        method = POST,
        uri = uri"/tasks",
        entity = """{"title":"To delete"}""",
        headers = Headers(Header("Content-Type", "application/json"))
      )
      for {
        _ <- app(createReq)
        deleteReq = Request[IO](method = DELETE, uri = uri"/tasks/1")
        deleteResp <- app(deleteReq)
        getReq = Request[IO](method = GET, uri = uri"/tasks/1")
        getResp <- app(getReq)
      } yield (deleteResp.status, getResp.status)
    }.unsafeRunSync()
    assertEquals(deleteStatus, NoContent)
    assertEquals(getStatus, NotFound)
  }
}
