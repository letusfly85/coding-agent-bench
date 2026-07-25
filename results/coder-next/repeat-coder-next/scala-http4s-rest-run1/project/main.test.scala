//> using scala 3.3.4
//> using test.dep org.scalameta::munit::1.0.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using dep io.circe::circe-generic:0.14.10

import munit.*
import cats.effect.IO
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.dsl.io.*
import org.http4s.implicits.*
import io.circe.parser.*
import io.circe.syntax.*

class MainTest extends FunSuite {
  private def runWithApp(f: HttpApp[IO] => IO[TestOutcome]) = 
    Main.freshApp.flatMap(f).unsafeRunSync()

  test("GET /health returns 200") {
    runWithApp { app =>
      val request = Request[IO](Method.GET, Uri(path = "/health"))
      app.run(request).map { response =>
        assertEquals(response.status, Status.Ok)
        val body = response.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertEquals(json.hcursor.get[String]("status"), Right("ok"))
        Outcome.succeeded
      }
    }
  }

  test("POST /tasks returns 201 with id 1") {
    runWithApp { app =>
      val createBody = Json.obj("title" -> Json.fromString("Test Task"))
      val request = Request[IO](Method.POST, Uri(path = "/tasks"))
        .withEntity(createBody)
        .withContentType(MediaType.ApplicationJson)

      app.run(request).map { response =>
        assertEquals(response.status, Status.Created)
        val body = response.as[String].unsafeRunSync()
        val json = parse(body).getOrElse(Json.Null)
        assertEquals(json.hcursor.get[Long]("id"), Right(1L))
        assertEquals(json.hcursor.get[String]("title"), Right("Test Task"))
        assertEquals(json.hcursor.get[Boolean]("done"), Right(false))
        Outcome.succeeded
      }
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    runWithApp { app =>
      val createBody = Json.obj("title" -> Json.fromString("Test Task"))
      val createRequest = Request[IO](Method.POST, Uri(path = "/tasks"))
        .withEntity(createBody)
        .withContentType(MediaType.ApplicationJson)

      app.run(createRequest)
        .flatMap { createResponse =>
          assertEquals(createResponse.status, Status.Created)
          val getTaskRequest = Request[IO](Method.GET, Uri(path = "/tasks/1"))
          app.run(getTaskRequest).map { getResponse =>
            assertEquals(getResponse.status, Status.Ok)
            val body = getResponse.as[String].unsafeRunSync()
            val json = parse(body).getOrElse(Json.Null)
            assertEquals(json.hcursor.get[Long]("id"), Right(1L))
            assertEquals(json.hcursor.get[String]("title"), Right("Test Task"))
            assertEquals(json.hcursor.get[Boolean]("done"), Right(false))
            Outcome.succeeded
          }
        }
    }
  }

  test("GET /tasks/999 returns 404") {
    runWithApp { app =>
      val request = Request[IO](Method.GET, Uri(path = "/tasks/999"))
      app.run(request).map { response =>
        assertEquals(response.status, Status.NotFound)
        Outcome.succeeded
      }
    }
  }

  test("DELETE existing task returns 204 and subsequent GET returns 404") {
    runWithApp { app =>
      val createBody = Json.obj("title" -> Json.fromString("To Delete"))
      val createRequest = Request[IO](Method.POST, Uri(path = "/tasks"))
        .withEntity(createBody)
        .withContentType(MediaType.ApplicationJson)

      app.run(createRequest)
        .flatMap { _ =>
          val deleteRequest = Request[IO](Method.DELETE, Uri(path = "/tasks/1"))
          app.run(deleteRequest).map { deleteResponse =>
            assertEquals(deleteResponse.status, Status.NoContent)
          }
        }
        .flatMap { _ =>
          val getRequest = Request[IO](Method.GET, Uri(path = "/tasks/1"))
          app.run(getRequest).map { getResponse =>
            assertEquals(getResponse.status, Status.NotFound)
            Outcome.succeeded
          }
        }
    }
  }
}
