//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import munit.CatsEffectSuite
import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import io.circe.parser._
import io.circe.syntax._

import scala.concurrent.ExecutionContext

class MainTest extends CatsEffectSuite {
  private implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  test("GET /health returns 200") {
    Main.freshApp.map { app =>
      val req = Request[IO](Method.GET, uri"/health")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
        val body = resp.as[String].unsafeRunSync()
        assertEquals(body, """{"status":"ok"}""")
      }
    }.flatten
  }

  test("POST /tasks returns 201 and id 1") {
    Main.freshApp.map { app =>
      val req = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = io.circe.jawn.decode[String]("""{"title":"Test task"}""").toOption.getOrElse("")
      ).withEntity("""{"title":"Test task"}""").withContentType(org.http4s.MediaType.application.json)
      
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Created)
        val body = resp.as[String].unsafeRunSync()
        val json = parse(body).toOption.getOrElse(throw new Exception("Invalid JSON"))
        assertEquals((json \ "id").as[Long], 1L)
        assertEquals((json \ "title").as[String], "Test task")
        assertEquals((json \ "done").as[Boolean], false)
      }
    }.flatten
  }

  test("GET /tasks/1 after creation returns the task") {
    Main.freshApp.map { app =>
      val createReq = Request[IO](
        method = Method.POST,
        uri = uri"/tasks",
        body = io.circe.jawn.decode[String]("""{"title":"First task"}""").toOption.getOrElse("")
      ).withEntity("""{"title":"First task"}""").withContentType(org.http4s.MediaType.application.json)
      
      app.run(createReq).use { _ =>
        val getReq = Request[IO](Method.GET, uri"/tasks/1")
        app.run(getReq).map { resp =>
          assertEquals(resp.status, Status.Ok)
          val body = resp.as[String].unsafeRunSync()
          val json = parse(body).toOption.getOrElse(throw new Exception("Invalid JSON"))
          assertEquals((json \ "id").as[Long], 1L)
          assertEquals((json \ "title").as[String], "First task")
          assertEquals((json \ "done").as[Boolean], false)
        }
      }
    }.flatten
  }

  test("GET /tasks/999 returns 404") {
    Main.freshApp.map { app =>
      val req = Request[IO](Method.GET, uri"/tasks/999")
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
    }.flatten
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    Main.freshApp.map { app =>
      for {
        _ <- app.run(
          Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            body = io.circe.jawn.decode[String]("""{"title":"Task to delete"}""").toOption.getOrElse("")
          ).withEntity("""{"title":"Task to delete"}""").withContentType(org.http4s.MediaType.application.json)
        ).use(_ => IO.unit)
        
        deleteResp <- app.run(
          Request[IO](Method.DELETE, uri"/tasks/1")
        )
        
        _ = assertEquals(deleteResp.status, Status.NoContent)
        
        getResp <- app.run(Request[IO](Method.GET, uri"/tasks/1"))
        _ = assertEquals(getResp.status, Status.NotFound)
      } yield ()
    }.flatten
  }
}
