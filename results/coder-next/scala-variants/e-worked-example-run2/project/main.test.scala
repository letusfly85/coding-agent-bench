//> using scala 3.3.4
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import munit.CatsEffectSuite
import cats.effect.{IO, Ref}
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser._

import TaskApi._

class TaskApiTest extends CatsEffectSuite:

  def buildApp: IO[(HttpApp[IO], Ref[IO, Map[Long, Task]], Ref[IO, Long])] =
    for
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield (routes(store, counter).orNotFound, store, counter)

  test("GET /health returns 200") {
    buildApp.flatMap { case (app, _, _) =>
      val req = Request[IO](Method.GET, uri"/health")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.as[String].compile.string.unsafeRunSync(), """{"status":"ok"}""")
      }
    }
  }

  test("POST /tasks returns 201 and id 1") {
    buildApp.flatMap { case (app, _, counter) =>
      val req = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = """{"title":"First task"}""".asJson.noSpaces
      )
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val task = resp.as[Task].compile.string.unsafeRunSync()
        val parsed = parse(task).toOption.flatMap(_.as[Task]).toOption
        assertEquals(parsed.map(_.id), Some(1L))
        assertEquals(parsed.map(_.title), Some("First task"))
        assertEquals(parsed.map(_.done), Some(false))
      }
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    buildApp.flatMap { case (app, store, _) =>
      for {
        // Create task first
        createReq = Request[IO](
          method = Method.POST,
          uri = uri"/tasks",
          body = """{"title":"Get milk"}""".asJson.noSpaces
        )
        _ <- app.run(createReq)

        // Then get it
        getReq = Request[IO](Method.GET, uri"/tasks/1")
        resp <- app.run(getReq)
      } yield {
        assertEquals(resp.status, Status.Ok)
        val task = resp.as[Task].compile.string.unsafeRunSync()
        val parsed = parse(task).toOption.flatMap(_.as[Task]).toOption
        assertEquals(parsed.map(_.id), Some(1L))
        assertEquals(parsed.map(_.title), Some("Get milk"))
      }
    }
  }

  test("GET /tasks/999 returns 404") {
    buildApp.flatMap { case (app, _, _) =>
      val req = Request[IO](Method.GET, uri"/tasks/999")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    buildApp.flatMap { case (app, _, _) =>
      for {
        // Create task first
        createReq = Request[IO](
          method = Method.POST,
          uri = uri"/tasks",
          body = """{"title":"Delete me"}""".asJson.noSpaces
        )
        _ <- app.run(createReq)

        // Delete task
        deleteReq = Request[IO](Method.DELETE, uri"/tasks/1")
        deleteResp <- app.run(deleteReq)

        // Verify deletion
        getReq = Request[IO](Method.GET, uri"/tasks/1")
        getResp <- app.run(getReq)
      } yield {
        assertEquals(deleteResp.status, Status.NoContent)
        assertEquals(getResp.status, Status.NotFound)
      }
    }
  }
