### FILE: main.scala
```scala
//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.syntax.all._
import io.circe.generic.auto._

case class Task(id: Long, title: String, done: Boolean)
case class CreateTask(title: String)
case class UpdateTask(title: String, done: Boolean)

object App {
  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "health" =>
        Ok("""{"status":"ok"}""")

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id)).flatMap(tasks => Ok(tasks))

      case req @ POST -> Root / "tasks" =>
        req.as[CreateTask].flatMap { ct =>
          for {
            id <- counter.getAndUpdate(_ + 1)
            task = Task(id, ct.title, false)
            _ <- store.update(_.updated(id, task))
          } yield Created(task)
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { map =>
          map.get(id) match {
            case Some(task) => Ok(task)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[UpdateTask].flatMap { ut =>
          store.get.flatMap { map =>
            map.get(id) match {
              case Some(existing) =>
                val updated = existing.copy(title = ut.title, done = ut.done)
                store.update(_.updated(id, updated)).as(Ok(updated))
              case None =>
                NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.getAndUpdate(_. - id).flatMap { oldMap =>
          if (oldMap.contains(id)) NoContent() else NotFound()
        }
    }
  }

  def freshApp: IO[HttpApp[IO]] = for {
    store <- Ref.of[IO, Map[Long, Task]](Map.empty)
    counter <- Ref.of[IO, Long](1L)
  } yield routes(store, counter).orNotFound
}

object Main extends IOApp.Simple {
  val run = App.freshApp.flatMap { app =>
    org.http4s.ember.server.EmberServerBuilder[IO]
      .bindHttp(3000, "0.0.0.0")
      .withHttpApp(app)
      .build
      .useForever
  }
}
```

### FILE: main.test.scala
```scala
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s._
import org.http4s.Method._
import org.http4s.Status._
import org.http4s.circe._
import org.http4s.syntax.all._
import io.circe.generic.auto._

class TaskApiSuite extends FunSuite {
  def createApp(): IO[HttpApp[IO]] = App.freshApp

  test("GET /health returns 200") {
    val status = createApp().flatMap { app =>
      val req = Request[IO](method = GET, uri = uri"/health")
      app(req).map(_.status)
    }.unsafeRunSync()
    assertEquals(status, Ok)
  }

  test("POST /tasks returns 201 and id 1") {
    val (status, id) = createApp().flatMap { app =>
      val req = Request[IO](
        method = POST,
        uri = uri"/tasks",
        entity = """{"title":"First task"}""",
        headers = Headers(Header("Content-Type", "application/json"))
      )
      app(req).flatMap { resp =>
        resp.as[Task].map(t => (resp.status, t.id))
      }
    }.unsafeRunSync()
    assertEquals(status, Created)
    assertEquals(id, 1L)
  }

  test("GET /tasks/1 after creation returns the task") {
    val title = createApp().flatMap { app =>
      val createReq = Request[IO](
        method = POST,
        uri = uri"/tasks",
        entity = """{"title":"Test"}""",
        headers = Headers(Header("Content-Type", "application/json"))
      )
      for {
        _ <- app(createReq)
        getReq = Request[IO](method = GET, uri = uri"/tasks/1")
        task <- app(getReq).flatMap(_.as[Task])
      } yield task.title
    }.unsafeRunSync()
    assertEquals(title, "Test")
  }

  test("GET /tasks/999 returns 404") {
    val status = createApp().flatMap { app =>
      val req = Request[IO](method = GET, uri = uri"/tasks/999")
      app(req).map(_.status)
    }.unsafeRunSync()
    assertEquals(status, NotFound)
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    val (deleteStatus, getStatus) = createApp().flatMap { app =>
      val createReq = Request[IO](
        method = POST,
        uri = uri"/tasks",
        entity = """{"title":"To delete"}""",
        headers = Headers(Header("Content-Type", "application/json"))
      )
      for {
        _ <- app(createReq)
        deleteReq = Request[IO](method = DELETE, uri = uri"/tasks/1")
        deleteResp <- app(deleteReq)
        getReq = Request[IO](method = GET, uri = uri"/tasks/1")
        getResp <- app(getReq)
      } yield (deleteResp.status, getResp.status)
    }.unsafeRunSync()
    assertEquals(deleteStatus, NoContent)
    assertEquals(getStatus, NotFound)
  }
}
```