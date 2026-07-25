import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.implicits._
import org.http4s.circe._
import io.circe.parser.decode
import io.circe.generic.auto._

class TaskApiSuite extends CatsEffectSuite {

  val app: IO[HttpApp[IO]] = Api.freshApp

  test("GET /health returns 200") {
    for {
      a <- app
      req = Request[IO](method = Method.GET, uri = uri"/health")
      resp <- a(req)
    } yield {
      assertEquals(resp.status, Status.Ok)
    }
  }

  test("POST /tasks returns 201 and id 1") {
    for {
      a <- app
      req = Request[IO](method = Method.POST, uri = uri"/tasks")
        .withEntity("""{"title":"First task"}""", MediaType.application.json)
      resp <- a(req)
      body <- resp.as[String]
      task = decode[Task](body).valueOr(e => throw new RuntimeException(e))
    } yield {
      assertEquals(resp.status, Status.Created)
      assertEquals(task.id, 1L)
      assertEquals(task.title, "First task")
      assertEquals(task.done, false)
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    for {
      a <- app
      createReq = Request[IO](method = Method.POST, uri = uri"/tasks")
        .withEntity("""{"title":"Test task"}""", MediaType.application.json)
      createResp <- a(createReq)
      _ <- createResp.body.drain
      getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
      getResp <- a(getReq)
      body <- getResp.as[String]
      task = decode[Task](body).valueOr(e => throw new RuntimeException(e))
    } yield {
      assertEquals(getResp.status, Status.Ok)
      assertEquals(task.title, "Test task")
    }
  }

  test("GET /tasks/999 returns 404") {
    for {
      a <- app
      req = Request[IO](method = Method.GET, uri = uri"/tasks/999")
      resp <- a(req)
    } yield {
      assertEquals(resp.status, Status.NotFound)
    }
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    for {
      a <- app
      createReq = Request[IO](method = Method.POST, uri = uri"/tasks")
        .withEntity("""{"title":"To delete"}""", MediaType.application.json)
      createResp <- a(createReq)
      _ <- createResp.body.drain
      deleteReq = Request[IO](method = Method.DELETE, uri = uri"/tasks/1")
      deleteResp <- a(deleteReq)
      getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
      getResp <- a(getReq)
    } yield {
      assertEquals(deleteResp.status, Status.NoContent)
      assertEquals(getResp.status, Status.NotFound)
    }
  }
}
