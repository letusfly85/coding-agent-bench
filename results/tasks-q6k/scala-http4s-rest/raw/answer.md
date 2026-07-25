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
import io.circe.generic.auto._
import io.circe.Json

case class Task(id: Long, title: String, done: Boolean)

object Api {
  case class TaskCreateRequest(title: String)
  case class TaskUpdateRequest(title: String, done: Boolean)

  def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> Json.fromString("ok")))

      case GET -> Root / "tasks" =>
        store.get.map(_.values.toSeq.sortBy(_.id).asJson).flatMap(Ok(_))

      case req @ POST -> Root / "tasks" =>
        req.as[TaskCreateRequest].flatMap { data =>
          counter.updateAndGet(_ + 1).flatMap { id =>
            val task = Task(id, data.title, done = false)
            store.update(_.updated(id, task)).as(Created(task))
          }
        }

      case GET -> Root / "tasks" / LongVar(id) =>
        store.get.flatMap { tasks =>
          tasks.get(id) match {
            case Some(task) => Ok(task)
            case None => NotFound()
          }
        }

      case req @ PUT -> Root / "tasks" / LongVar(id) =>
        req.as[TaskUpdateRequest].flatMap { data =>
          store.get.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) =>
                val updated = task.copy(title = data.title, done = data.done)
                store.update(_.updated(id, updated)).as(Ok(updated))
              case None => NotFound()
            }
          }
        }

      case DELETE -> Root / "tasks" / LongVar(id) =>
        store.updateAndGet(_. - id).flatMap { (prev, _) =>
          if (prev.contains(id)) NoContent()
          else NotFound()
        }
    }

  def freshApp: IO[HttpApp[IO]] = for {
    store <- Ref.of[IO, Map[Long, Task]](Map.empty)
    counter <- Ref.of[IO, Long](0L)
    app = routes(store, counter).orNotFound
  } yield app
}

object Main extends IOApp.Simple {
  val run: IO[Unit] = for {
    app <- Api.freshApp
    server <- org.http4s.ember.server.EmberServerBuilder[IO]
      .bindHttp(3000, "0.0.0.0")
      .withHttpApp(app)
      .build
    _ <- server.use(_ => IO.never)
  } yield ()
}
```

### FILE: main.test.scala
```scala
import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.syntax.all._
import io.circe.parser._
import io.circe.syntax._
import Api._
import scala.util.Try

class TaskApiSuite extends FunSuite {
  var app: HttpApp[IO] = _

  override def beforeEach(context: BeforeEach): Unit = {
    app = freshApp.unsafeRunSync()
  }

  private def runRequest(req: Request[IO]): IO[Response[IO]] =
    app(req)

  test("GET /health returns 200") {
    val req = Request[IO](Method.GET, uri"/health")
    val resp = runRequest(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val body = decode[Map[String, String]](resp.bodyText.unsafeRunSync()).toTry.get
    assertEquals(body("status"), "ok")
  }

  test("POST /tasks returns 201 and id 1") {
    val json = TaskCreateRequest("First task").asJson.noSpaces
    val req = Request[IO](Method.POST, uri"/tasks").withEntity(json)
    val resp = runRequest(req).unsafeRunSync()
    assertEquals(resp.status, Status.Created)
    val task = decode[Task](resp.bodyText.unsafeRunSync()).toTry.get
    assertEquals(task.id, 1L)
    assertEquals(task.title, "First task")
    assertEquals(task.done, false)
  }

  test("GET /tasks/1 after creation returns the task") {
    val json = TaskCreateRequest("Get me").asJson.noSpaces
    val createReq = Request[IO](Method.POST, uri"/tasks").withEntity(json)
    runRequest(createReq).unsafeRunSync()

    val getReq = Request[IO](Method.GET, uri"/tasks/1")
    val resp = runRequest(getReq).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val task = decode[Task](resp.bodyText.unsafeRunSync()).toTry.get
    assertEquals(task.title, "Get me")
  }

  test("GET /tasks/999 returns 404") {
    val req = Request[IO](Method.GET, uri"/tasks/999")
    val resp = runRequest(req).unsafeRunSync()
    assertEquals(resp.status, Status.NotFound)
  }

  test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
    val json = TaskCreateRequest("Delete me").asJson.noSpaces
    val createReq = Request[IO](Method.POST, uri"/tasks").withEntity(json)
    val createResp = runRequest(createReq).unsafeRunSync()
    val task = decode[Task](createResp.bodyText.unsafeRunSync()).toTry.get

    val deleteReq = Request[IO](Method.DELETE, uri"/tasks/${task.id}")
    val deleteResp = runRequest(deleteReq).unsafeRunSync()
    assertEquals(deleteResp.status, Status.NoContent)

    val getReq = Request[IO](Method.GET, uri"/tasks/${task.id}")
    val getResp = runRequest(getReq).unsafeRunSync()
    assertEquals(getResp.status, Status.NotFound)
  }
}
```