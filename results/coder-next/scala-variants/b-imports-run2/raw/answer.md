### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep org.http4s::http4s-ember-client:0.23.30
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

import java.util.concurrent.atomic.AtomicLong

case class Task(id: Long, title: String, done: Boolean)

object Main extends IOApp.Simple {
  def routes(store: Ref[IO, List[Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    val taskService = new {
      val httpRoutes = Routes(
        GET(Path / "health") {
          Ok(Json.obj("status" -> Json.fromString("ok")))
        },
        GET(Path / "tasks") {
          store.get.map { tasks =>
            Ok(tasks.sortBy(_.id).asJson)
          }
        },
        POST(Path / "tasks") {
          for {
            title <- req => req.as[String]
            id <- counter.getAndModify(_ + 1)
            task = Task(id, title, done = false)
            _ <- store.update(tasks => task :: tasks)
          } yield Created(task.asJson)
        },
        GET(Path / "tasks" / IntVar(id)) {
          store.get.map { tasks =>
            tasks.find(_.id == id) match {
              case Some(task) => Ok(task.asJson)
              case None => NotFound()
            }
          }
        },
        PUT(Path / "tasks" / IntVar(id)) {
          for {
            taskUpdate <- req => req.as[Task]
            _ <- store.modify { tasks =>
              tasks.find(_.id == id) match {
                case Some(_) =>
                  val updated = taskUpdate.copy(id = id)
                  (updated :: tasks.filterNot(_.id == id), updated)
                case None => (tasks, null)
              }
            } flatMap {
              case null => NotFound().pure[IO]
              case updated => Ok(updated.asJson).pure[IO]
            }
          } yield ()
        },
        DELETE(Path / "tasks" / IntVar(id)) {
          store.modify { tasks =>
            val (found, remaining) = tasks.partition(_.id == id)
            if (found.nonEmpty) (remaining, Status.NoContent)
            else (tasks, Status.NotFound)
          }.flatMap(_.pure[IO])
        }
      )
    }.httpRoutes

    taskService
  }

  def freshApp: IO[HttpApp[IO]] = {
    for {
      store <- Ref.of[IO, List[Task]](List.empty)
      counter <- Ref.of[IO, Long](1L)
      router = routes(store, counter)
      app = router.orNotFound
    } yield app
  }

  def run: IO[Unit] =
    freshApp.flatMap(app => 
      EmberServerBuilder
        .default[IO]
        .withHost(ip"0.0.0.0")
        .withPort(port"3000")
        .withHttpApp(app)
        .build
        .use(_.useForever)
    )
}
```

### FILE: main.test.scala
```scala
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
```