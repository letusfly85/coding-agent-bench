//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4

import munit.CatsEffectSuite
import org.http4s.Method._
import org.http4s.Request
import org.http4s.Uri.Path
import org.http4s.dsl.io._
import org.http4s.implicits._

import taskapi.{Main, Task}

class MainSuite extends CatsEffectSuite {
  private val baseUri = org.http4s.Uri(path = Path.Root)

  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      val req = Request[IO](GET, baseUri / "health")
      app.run(req).map { resp =>
        assertEquals(resp.status.code, 200)
        assertEquals(resp.as[String].unsafeRunSync(), """{"status":"ok"}""")
      }
    }.flatten
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      val taskJson = """{"title":"Test task"}"""
      val req = Request[IO](
        method = POST,
        uri = baseUri / "tasks",
        body = io.circe.syntax._,
        headers = org.http4s.Headers(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))
      ).withEntity(taskJson)

      app.run(req).map { resp =>
        assertEquals(resp.status.code, 201)
        val task = resp.as[Task].unsafeRunSync()
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Test task")
        assertEquals(task.done, false)
      }
    }.flatten
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = POST,
        uri = baseUri / "tasks"
      ).withEntity("""{"title":"Get me"}""")

      for {
        _ <- app.run(createReq).map(_.as[Task].unsafeRunSync())
        getReq = Request[IO](GET, baseUri / "tasks" / "1")
        resp <- app.run(getReq)
        task <- resp.as[Task]
      } yield {
        assertEquals(resp.status.code, 200)
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Get me")
        assertEquals(task.done, false)
      }
    }.flatten
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      val req = Request[IO](GET, baseUri / "tasks" / "999")
      app.run(req).map { resp =>
        assertEquals(resp.status.code, 404)
      }
    }.flatten
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = POST,
        uri = baseUri / "tasks"
      ).withEntity("""{"title":"To delete"}""")

      for {
        _ <- app.run(createReq).map(_.as[Task].unsafeRunSync())
        deleteReq = Request[IO](DELETE, baseUri / "tasks" / "1")
        deleteResp <- app.run(deleteReq)
        getReq = Request[IO](GET, baseUri / "tasks" / "1")
        getResp <- app.run(getReq)
      } yield {
        assertEquals(deleteResp.status.code, 204)
        assertEquals(getResp.status.code, 404)
      }
    }.flatten
  }
}
