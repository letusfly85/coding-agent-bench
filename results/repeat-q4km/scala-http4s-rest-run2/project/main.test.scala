import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe._
import io.circe.generic.auto._
import io.circe.syntax._

class ApiTests extends FunSuite {

  test("GET /health returns 200") {
    App.freshApp.flatMap { app =>
      val req = Request[IO](method = Method.GET, uri = uri"/health")
      app.run(req).use { resp =>
        IO(assertEquals(resp.status, Status.Ok))
      }
    }.unsafeRunSync()
  }

  test("POST /tasks returns 201 and id 1") {
    App.freshApp.flatMap { app =>
      val req = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        entity = """{"title":"Test Task"}"""
      )
      app.run(req).use { resp =>
        for {
          _ <- IO(assertEquals(resp.status, Status.Created))
          task <- resp.as[Task]
          _ <- IO(assertEquals(task.id, 1L))
          _ <- IO(assertEquals(task.title, "Test Task"))
          _ <- IO(assertEquals(task.done, false))
        } yield ()
      }
    }.unsafeRunSync()
  }

  test("GET /tasks/1 after creation returns the task") {
    App.freshApp.flatMap { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        entity = """{"title":"Created"}"""
      )
      for {
        _ <- app.run(createReq).use(_ => IO.unit)
        getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
        resp <- app.run(getReq)
        task <- resp.as[Task]
        _ <- IO(assertEquals(resp.status, Status.Ok))
        _ <- IO(assertEquals(task.title, "Created"))
      } yield ()
    }.unsafeRunSync()
  }

  test("GET /tasks/999 returns 404") {
    App.freshApp.flatMap { app =>
      val req = Request[IO](method = Method.GET, uri = uri"/tasks/999")
      app.run(req).use { resp =>
        IO(assertEquals(resp.status, Status.NotFound))
      }
    }.unsafeRunSync()
  }

  test("DELETE existing task returns 204, subsequent GET returns 404") {
    App.freshApp.flatMap { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        entity = """{"title":"To Delete"}"""
      )
      for {
        _ <- app.run(createReq).use(_ => IO.unit)
        deleteReq = Request[IO](method = Method.DELETE, uri = uri"/tasks/1")
        delResp <- app.run(deleteReq)
        _ <- IO(assertEquals(delResp.status, Status.NoContent))
        getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
        getResp <- app.run(getReq)
        _ <- IO(assertEquals(getResp.status, Status.NotFound))
      } yield ()
    }.unsafeRunSync()
  }
}
