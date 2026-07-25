import munit.CatsEffectSuite
import cats.effect.IO
import org.http4s.{Method, Request, Uri}
import org.http4s.circe._
import io.circe.generic.auto._
import io.circe.syntax._

class TaskApiSuite extends CatsEffectSuite {

  test("GET /health returns 200") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](uri = Uri.unsafeFromString("/health"))
      resp <- app.run(req)
      body <- resp.as[String]
    } yield {
      assertEquals(resp.status.code, 200)
      assert(body.contains("\"status\":\"ok\""))
    }
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("Test").asJson
      )
      resp <- app.run(req)
      task <- resp.as[Task]
    } yield {
      assertEquals(resp.status.code, 201)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "Test")
      assertEquals(task.done, false)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("First").asJson
      ))
      getReq = Request[IO](uri = Uri.unsafeFromString("/tasks/1"))
      resp <- app.run(getReq)
      task <- resp.as[Task]
    } yield {
      assertEquals(resp.status.code, 200)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "First")
    }
  }

  test("GET /tasks/999 returns 404") {
    for {
      app <- TaskApi.freshApp
      req = Request[IO](uri = Uri.unsafeFromString("/tasks/999"))
      resp <- app.run(req)
    } yield {
      assertEquals(resp.status.code, 404)
    }
  }

  test("DELETE existing task returns 204, subsequent GET returns 404") {
    for {
      app <- TaskApi.freshApp
      _ <- app.run(Request[IO](
        method = Method.POST,
        uri = Uri.unsafeFromString("/tasks"),
        entity = TaskInput("ToDelete").asJson
      ))
      delResp <- app.run(Request[IO](
        method = Method.DELETE,
        uri = Uri.unsafeFromString("/tasks/1")
      ))
      getResp <- app.run(Request[IO](uri = Uri.unsafeFromString("/tasks/1")))
    } yield {
      assertEquals(delResp.status.code, 204)
      assertEquals(getResp.status.code, 404)
    }
  }
}
