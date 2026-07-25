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
        store.get.map(tasks => 
          Ok(tasks.values.toList.sortBy(_.id).asJson)
        )
        
      case req @ POST -> Root / "tasks" => 
        for {
          CreateTaskReq(title) <- req.as[CreateTaskReq]
          id <- counter.updateAndGet(_ + 1)
          task = Task(id, title, done = false)
          _ <- store.update(_.updated(id, task))
          response <- Created(task)
        } yield response
        
      case GET -> Root / "tasks" / LongVar(id) => 
        store.get.map { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task)
            case None => NotFound()
          }
        }
        
      case req @ PUT -> Root / "tasks" / LongVar(id) => 
        for {
          UpdateTaskReq(title, done) <- req.as[UpdateTaskReq]
          task <- store.modify { tasks =>
            tasks.get(id) match {
              case Some(_) => 
                val updatedTask = Task(id, title, done)
                (tasks.updated(id, updatedTask), Some(updatedTask))
              case None => 
                (tasks, None)
            }
          }
          response <- task match {
            case Some(updatedTask) => Ok(updatedTask)
            case None => NotFound()
          }
        } yield response
        
      case DELETE -> Root / "tasks" / LongVar(id) => 
        store.modify { tasks =>
          tasks.get(id) match {
            case Some(_) => 
              (tasks - id, Status.NoContent)
            case None => 
              (tasks, Status.NotFound)
          }
        }.map(response => response)
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
//> using test.dep org.scalameta::munit::1.0.4

import munit.CatsEffectSuite
import cats.effect.{IO, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe.CirceEntityCodec._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.Method._
import org.http4s.Request
import org.http4s.ember.client.EmberClientBuilder
import java.net.InetSocketAddress

class MainSuite extends CatsEffectSuite:
  test("GET /health returns 200") {
    val testApp = Api.freshApp
    testApp.flatMap { app =>
      val request = Request[IO](method = GET, uri = uri"/health")
      request.run(app).map { response =>
        assertEquals(response.status, Status.Ok)
        assert(response.body.asString.compile.lastOrError.contains("""{"status":"ok"}"""))
      }
    }
  }

  test("POST /tasks returns 201 and id 1") {
    val testApp = Api.freshApp
    testApp.flatMap { app =>
      val createTaskReq = CreateTaskReq("Test task")
      val request = Request[IO](
        method = POST,
        uri = uri"/tasks",
        body = createTaskReq.asJson.noSpaces
      ).withHeaders(headers.`Content-Type`(media.`application/json`))
      request.run(app).map { response =>
        assertEquals(response.status, Status.Created)
        val task = response.as[Task].compile.lastOrError
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Test task")
        assertEquals(task.done, false)
      }
    }
  }

  test("GET /tasks/1 after creation returns the task") {
    val testApp = Api.freshApp
    testApp.flatMap { app =>
      val createTaskReq = CreateTaskReq("Test task")
      val createRequest = Request[IO](
        method = POST,
        uri = uri"/tasks",
        body = createTaskReq.asJson.noSpaces
      ).withHeaders(headers.`Content-Type`(media.`application/json`))
      
      for {
        _ <- createRequest.run(app)
        getRequest = Request[IO](method = GET, uri = uri"/tasks/1")
        response <- getRequest.run(app)
        task = response.as[Task].compile.lastOrError
      } yield {
        assertEquals(response.status, Status.Ok)
        assertEquals(task.id, 1L)
        assertEquals(task.title, "Test task")
        assertEquals(task.done, false)
      }
    }
  }

  test("GET /tasks/999 returns 404") {
    val testApp = Api.freshApp
    testApp.flatMap { app =>
      val request = Request[IO](method = GET, uri = uri"/tasks/999")
      request.run(app).map { response =>
        assertEquals(response.status, Status.NotFound)
      }
    }
  }

  test("DELETE an existing task returns 204, and subsequent GET returns 404") {
    val testApp = Api.freshApp
    testApp.flatMap { app =>
      val createTaskReq = CreateTaskReq("Delete me")
      val createRequest = Request[IO](
        method = POST,
        uri = uri"/tasks",
        body = createTaskReq.asJson.noSpaces
      ).withHeaders(headers.`Content-Type`(media.`application/json`))
      
      for {
        _ <- createRequest.run(app)
        deleteRequest = Request[IO](method = DELETE, uri = uri"/tasks/1")
        deleteResponse <- deleteRequest.run(app)
        getAfterDelete = Request[IO](method = GET, uri = uri"/tasks/1")
        getResponse <- getAfterDelete.run(app)
      } yield {
        assertEquals(deleteResponse.status, Status.NoContent)
        assertEquals(getResponse.status, Status.NotFound)
      }
    }
  }
```