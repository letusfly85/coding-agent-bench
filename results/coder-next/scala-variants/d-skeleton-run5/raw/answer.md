### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import io.circe.generic.auto._
import com.comcast.ip4s._

case class Task(id: Long, title: String, done: Boolean)
case class CreateTaskReq(title: String)
case class UpdateTaskReq(title: String, done: Boolean)

object Api:
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" => 
        Ok(Json.obj("status" -> Json.fromString("ok")))
      
      case GET -> Root / "tasks" => 
        store.get.map { tasks =>
          val sortedTasks = tasks.values.toList.sortBy(_.id)
          Ok(sortedTasks.asJson)
        }
      
      case req @ POST -> Root / "tasks" => 
        for
          reqBody <- req.as[CreateTaskReq]
          id <- counter.updateAndGet(_ + 1)
          task = Task(id, reqBody.title, done = false)
          _ <- store.update(_ + (id -> task))
        yield Created(task.asJson)
      
      case GET -> Root / "tasks" / LongVar(id) => 
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task.asJson)
            case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
          }
        }
      
      case req @ PUT -> Root / "tasks" / LongVar(id) => 
        for
          reqBody <- req.as[UpdateTaskReq]
          result <- store.modify { tasks =>
            tasks.get(id) match {
              case Some(_) =>
                val updatedTask = Task(id, reqBody.title, reqBody.done)
                (tasks.updated(id, updatedTask), Some(updatedTask))
              case None =>
                (tasks, None)
            }
          }
        yield result match {
          case Some(task) => Ok(task.asJson)
          case None => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
        }
      
      case DELETE -> Root / "tasks" / LongVar(id) => 
        store.modify { tasks =>
          if (tasks.contains(id)) {
            (tasks - id, Status.NoContent)
          } else {
            (tasks, Status.NotFound)
          }
        }.map { status =>
          status match {
            case Status.NoContent => NoContent()
            case Status.NotFound => NotFound(Json.obj("error" -> Json.fromString("Task not found")))
            case _ => InternalServerError()
          }
        }
    }

  def freshApp: IO[HttpApp[IO]] =
    for
      store   <- Ref.of[IO, Map[Long, Task]](Map.empty)
      counter <- Ref.of[IO, Long](0L)
    yield routes(store, counter).orNotFound

object Main extends IOApp.Simple:
  val run: IO[Unit] =
    Api.freshApp.flatMap { app =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .useForever
    }
```

### FILE: main.test.scala
```scala
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
```