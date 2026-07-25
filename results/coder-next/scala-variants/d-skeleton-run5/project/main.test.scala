//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using dep org.http4s::http4s-ember-client:0.23.30
//> using test.dep org.scalameta::munit::1.0.4

import munit.*
import cats.effect.*
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.dsl.io.*
import io.circe.syntax.*
import io.circe.parser.*
import com.comcast.ip4s._

class ApiTest extends munit.CatsEffectSuite:

  def createClient(app: HttpApp[IO]): Resource[IO, Client[IO]] =
    org.http4s.client.dsl.io.fromHttpApp(app).resource

  val testApp: Resource[IO, HttpApp[IO]] =
    Resource.pure[IO, HttpApp[IO]](Api.freshApp.blockingGet)

  test("GET /health returns 200") {
    testApp.use { app =>
      val client = org.http4s.client.dsl.io.fromHttpApp(app)
      client.get(uri"/health").use { response =>
        assertEquals(response.status, Status.Ok)
      }
    }
  }

  test("POST /tasks returns 201 and id 1") {
    testApp.use { app =>
      val client = org.http4s.client.dsl.io.fromHttpApp(app)
      val body = """{"title":"Test task"}"""
      client.post(uri"/tasks", body).use { response =>
        for {
          _ <- IO(assertEquals(response.status, Status.Created))
          json <- response.as[io.circe.Json]
          id <- IO(json.hcursor.downField("id").as[Long].toOption.flatten)
          title <- IO(json.hcursor.downField("title").as[String].toOption.getOrElse(""))
          done <- IO(json.hcursor.downField("done").as[Boolean].toOption.getOrElse(false))
        } yield {
          assertEquals(id, Some(1L))
          assertEquals(title, "Test task")
          assertEquals(done, false)
        }
      }
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    testApp.use { app =>
      val client = org.http4s.client.dsl.io.fromHttpApp(app)
      val createBody = """{"title":"Test task"}"""
      for {
        _ <- client.post(uri"/tasks", createBody).use(_ => IO.unit)
        response <- client.get(uri"/tasks/1")
        _ <- IO(assertEquals(response.status, Status.Ok))
        json <- response.as[io.circe.Json]
        id <- IO(json.hcursor.downField("id").as[Long].toOption.flatten)
        title <- IO(json.hcursor.downField("title").as[String].toOption.getOrElse(""))
        done <- IO(json.hcursor.downField("done").as[Boolean].toOption.getOrElse(false))
      } yield {
        assertEquals(id, Some(1L))
        assertEquals(title, "Test task")
        assertEquals(done, false)
      }
    }
  }

  test("GET /tasks/999 returns 404") {
    testApp.use { app =>
      val client = org.http4s.client.dsl.io.fromHttpApp(app)
      client.get(uri"/tasks/999").use { response =>
        assertEquals(response.status, Status.NotFound)
      }
    }
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    testApp.use { app =>
      val client = org.http4s.client.dsl.io.fromHttpApp(app)
      val createBody = """{"title":"To delete"}"""
      for {
        _ <- client.post(uri"/tasks", createBody).use(_ => IO.unit)
        deleteResponse <- client.delete(uri"/tasks/1")
        _ <- IO(assertEquals(deleteResponse.status, Status.NoContent))
        getResponse <- client.get(uri"/tasks/1")
      } yield assertEquals(getResponse.status, Status.NotFound)
    }
  }
