Here's a thinking process:

1.  **Understand the Goal**: The user wants a small REST API in Scala 3 using http4s, built with scala-cli. The requirements are very specific about dependencies, data model, endpoints, implementation constraints, and tests. I need to output exactly two files: `main.scala` and `main.test.scala` with the specified format.

2.  **Analyze Requirements**:
    - **Build**: scala-cli directives at the top of `main.scala`.
    - **Dependencies**:
      - Scala 3.3.4
      - http4s-ember-server:0.23.30
      - http4s-dsl:0.23.30
      - http4s-circe:0.23.30
      - circe-generic:0.14.10
      - munit:1.0.4 (test)
    - **Data Model**: `Task(id: Long, title: String, done: Boolean)`
    - **Endpoints**:
      - `GET /health` -> 200 `{"status":"ok"}`
      - `GET /tasks` -> 200 JSON array (sorted by id)
      - `POST /tasks` -> body `{"title":"..."}` -> 201 created task (`done=false`)
      - `GET /tasks/{id}` -> 200 or 404
      - `PUT /tasks/{id}` -> body `{"title":"...","done":true}` -> 200 or 404
      - `DELETE /tasks/{id}` -> 204 or 404
    - **Constraints**:
      - Use `cats.effect.IO` and `HttpRoutes[IO]`
      - In-memory state with `Ref[IO, ...]`
      - Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`
      - Expose `def freshApp: IO[HttpApp[IO]]`
      - Derive circe codecs
      - `object Main extends IOApp.Simple` on port 3000 via Ember
    - **Tests** (in `main.test.scala`):
      - Use munit
      - Drive `HttpApp[IO]` directly (no TCP)
      - Cover: health, POST 201/id=1, GET /tasks/1, GET /tasks/999 -> 404, DELETE + GET -> 404

3.  **Design Implementation (`main.scala`)**:
    - Imports: scala-cli directives, cats.effect, http4s, circe, etc.
    - Data model: `Task` case class with circe codecs.
    - Store type: `Map[Long, Task]`
    - `routes` function:
      - Uses `dsl._` for routing
      - Health: `GET -> path("health") -> Ok(Json.obj("status" -> Json.fromString("ok")))`
      - GET /tasks: `GET -> path("tasks") -> Ok(store.get.map(_.values.toList.sortBy(_.id).asJson))`
      - POST /tasks: `POST -> path("tasks") -> as[CreateTask] -> ...`
        - Need a case class for input: `case class CreateTask(title: String)`
        - Increment counter, create task, update store, return 201
      - GET /tasks/:id: `GET -> path("tasks" / Long) -> ...`
      - PUT /tasks/:id: `PUT -> path("tasks" / Long) -> as[UpdateTask] -> ...`
        - Input: `case class UpdateTask(title: String, done: Boolean)`
      - DELETE /tasks/:id: `DELETE -> path("tasks" / Long) -> ...`
    - `freshApp`: Creates `Ref` for store and counter, calls `routes`, wraps in `HttpApp` (maybe with `orNotFound` or just `routes.orNotFound`)
    - `Main`: Uses `EmberServerBuilder[IO]` to start on port 3000 with `freshApp.unsafeRunSync()` or similar. Actually, `IOApp.Simple` requires `def run: IO[ExitCode]`. We'll run the server and keep it alive.

    Let's draft the code mentally:
    ```scala
    //> using scala 3.3.4
    //> using dep org.http4s::http4s-ember-server:0.23.30
    //> using dep org.http4s::http4s-dsl:0.23.30
    //> using dep org.http4s::http4s-circe:0.23.30
    //> using dep io.circe::circe-generic:0.14.10
    //> using test.dep org.scalameta::munit::1.0.4

    import cats.effect.{IO, IOApp, Ref}
    import cats.effect.std.Random
    import cats.syntax.all._
    import org.http4s._
    import org.http4s.dsl.io._
    import org.http4s.implicits._
    import org.http4s.server.middleware._
    import org.http4s.ember.server.EmberServerBuilder
    import io.circe._
    import io.circe.generic.auto._
    import io.circe.syntax._

    case class Task(id: Long, title: String, done: Boolean)
    case class CreateTask(title: String)
    case class UpdateTask(title: String, done: Boolean)

    object App {
      type TaskStore = Map[Long, Task]

      def routes(store: Ref[IO, TaskStore], counter: Ref[IO, Long]): HttpRoutes[IO] = {
        val health = GET(path("health")) {
          Ok(Json.obj("status" -> Json.fromString("ok")))
        }

        val getAll = GET(path("tasks")) {
          store.get.map { tasks =>
            Ok(tasks.values.toList.sortBy(_.id).asJson)
          }
        }

        val create = POST(path("tasks")) {
          as[CreateTask].flatMap { input =>
            counter.update(_ + 1).flatMap { id =>
              val task = Task(id, input.title, done = false)
              store.update(_.updated(id, task)).as(Created(task))
            }
          }
        }

        val getById = GET(path("tasks" / Long)) { id =>
          store.get.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task)
              case None => NotFound()
            }
          }
        }

        val update = PUT(path("tasks" / Long)) { id =>
          as[UpdateTask].flatMap { input =>
            store.get.flatMap { tasks =>
              tasks.get(id) match {
                case Some(existing) =>
                  val updated = existing.copy(title = input.title, done = input.done)
                  store.update(_.updated(id, updated)).as(Ok(updated))
                case None => NotFound()
              }
            }
          }
        }

        val delete = DELETE(path("tasks" / Long)) { id =>
          store.updateAndGet(_. - id).flatMap { prev =>
            if (prev.contains(id)) NoContent()
            else NotFound()
          }
        }

        health ++ getAll ++ create ++ getById ++ update ++ delete
      }

      def freshApp: IO[HttpApp[IO]] = {
        for {
          store <- Ref.of[IO, TaskStore](Map.empty)
          counter <- Ref.of[IO, Long](0L)
        } yield routes(store, counter).orNotFound
      }
    }

    object Main extends IOApp.Simple {
      val run: IO[ExitCode] = {
        App.freshApp.flatMap { app =>
          EmberServerBuilder
            .default[IO]
            .withHost("0.0.0.0")
            .withPort(3000)
            .withHttpApp(app)
            .build
            .use(_ => IO.never)
            .as(ExitCode.Success)
        }
      }
    }
    ```

    Wait, check constraints:
    - "Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`, plus a helper `def freshApp: IO[HttpApp[IO]]`"
    - I put them in `object App`. That's fine. Or I can put them at top level. I'll keep them in `object App` or just top-level. The prompt says "Expose def routes...". I'll put them in `object App` for cleanliness, or just top-level. I'll stick with `object App`.
    - Check circe imports: `io.circe.generic.auto._` works for Scala 3? Yes, but sometimes needs explicit derivation. I'll use `io.circe.generic.auto._` as suggested.
    - Check http4s DSL: `GET(path("health")) { Ok(...) }` is correct for 0.23.x.
    - Check `as[CreateTask]`: http4s-dsl provides `as[T]` for JSON decoding when circe is on classpath. Yes.
    - Check `store.updateAndGet`: `Ref` has `updateAndGet` in cats-effect 3. Yes.
    - Check `orNotFound`: `routes.orNotFound` converts `HttpRoutes` to `HttpApp`. Yes.
    - Check `IOApp.Simple`: requires `run: IO[ExitCode]`. Yes.
    - Check port: 3000. Yes.
    - Check dependencies: exactly as specified. Yes.

    One minor thing: `io.circe.generic.auto._` might conflict with Scala 3 macros sometimes, but for simple case classes it's fine. Alternatively, I can use explicit `Encoder`/`Decoder` if needed, but the prompt says "e.g. io.circe.generic.auto._ or explicit Encoder/Decoder". I'll stick with `auto._`.

    Let's verify the DSL syntax for http4s 0.23.30:
    ```scala
    import org.http4s.dsl.io._
    // ...
    val health = GET(path("health")) { Ok(Json.obj("status" -> Json.fromString("ok"))) }
    ```
    This is correct.

    Wait, `GET(path("health"))` expects a block that returns `F[Response[F]]`. `Ok(...)` is a `Response[IO]`. So it should be:
    ```scala
    val health = GET(path("health")) {
      Ok(Json.obj("status" -> Json.fromString("ok")))
    }
    ```
    Actually, in http4s-dsl, `Ok` is a method that takes `A` and uses `EntityEncoder`. So `Ok(Json.obj(...))` works.

    Let's double check `as[CreateTask]`:
    ```scala
    val create = POST(path("tasks")) {
      as[CreateTask].flatMap { input => ... }
    }
    ```
    This is correct.

    One detail: `store.updateAndGet(_. - id)` returns `IO[TaskStore]`. Then we check `prev.contains(id)`. That's fine.

    Let's structure `main.scala` carefully.

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
    import org.http4s.ember.server.EmberServerBuilder
    import io.circe._
    import io.circe.generic.auto._
    import io.circe.syntax._

    case class Task(id: Long, title: String, done: Boolean)
    case class CreateTask(title: String)
    case class UpdateTask(title: String, done: Boolean)

    object App {
      type TaskStore = Map[Long, Task]

      def routes(store: Ref[IO, TaskStore], counter: Ref[IO, Long]): HttpRoutes[IO] = {
        val health = GET(path("health")) {
          Ok(Json.obj("status" -> Json.fromString("ok")))
        }

        val getAll = GET(path("tasks")) {
          store.get.map { tasks =>
            Ok(tasks.values.toList.sortBy(_.id).asJson)
          }
        }

        val create = POST(path("tasks")) {
          as[CreateTask].flatMap { input =>
            counter.update(_ + 1).flatMap { id =>
              val task = Task(id, input.title, done = false)
              store.update(_.updated(id, task)).as(Created(task))
            }
          }
        }

        val getById = GET(path("tasks" / Long)) { id =>
          store.get.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task)
              case None => NotFound()
            }
          }
        }

        val update = PUT(path("tasks" / Long)) { id =>
          as[UpdateTask].flatMap { input =>
            store.get.flatMap { tasks =>
              tasks.get(id) match {
                case Some(existing) =>
                  val updated = existing.copy(title = input.title, done = input.done)
                  store.update(_.updated(id, updated)).as(Ok(updated))
                case None => NotFound()
              }
            }
          }
        }

        val delete = DELETE(path("tasks" / Long)) { id =>
          store.updateAndGet(_. - id).flatMap { prev =>
            if (prev.contains(id)) NoContent()
            else NotFound()
          }
        }

        health ++ getAll ++ create ++ getById ++ update ++ delete
      }

      def freshApp: IO[HttpApp[IO]] = {
        for {
          store <- Ref.of[IO, TaskStore](Map.empty)
          counter <- Ref.of[IO, Long](0L)
        } yield routes(store, counter).orNotFound
      }
    }

    object Main extends IOApp.Simple {
      val run: IO[ExitCode] = App.freshApp.flatMap { app =>
        EmberServerBuilder
          .default[IO]
          .withHost("0.0.0.0")
          .withPort(3000)
          .withHttpApp(app)
          .build
          .use(_ => IO.never)
          .as(ExitCode.Success)
      }
    }
    ```

    Check constraints:
    - "Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`" -> Yes.
    - "plus a helper `def freshApp: IO[HttpApp[IO]]`" -> Yes.
    - "Derive circe codecs for Task" -> Yes, via `generic.auto._`.
    - "Provide an `object Main extends IOApp.Simple` that serves on port 3000 via Ember." -> Yes.
    - Dependencies match exactly.
    - All endpoints match.

    Now, `main.test.scala`:
    Requirements:
    - Use munit
    - Drive `HttpApp[IO]` directly
    - No TCP
    - Cover:
      1. GET /health returns 200
      2. POST /tasks returns 201 and id 1
      3. GET /tasks/1 after creation returns the task
      4. GET /tasks/999 returns 404
      5. DELETE existing task returns 204, subsequent GET returns 404

    Draft test code:
    ```scala
    package test

    import cats.effect.IO
    import cats.effect.unsafe.implicits.global
    import munit.FunSuite
    import org.http4s._
    import org.http4s.dsl.io._
    import org.http4s.implicits._
    import io.circe.generic.auto._
    import io.circe.syntax._

    class ApiTests extends FunSuite {

      private def runTest(name: String)(block: App.HttpApp[IO] => IO[Unit]): Unit = {
        test(name) {
          App.freshApp.flatMap(block).unsafeRunSync()
        }
      }

      runTest("GET /health returns 200") { app =>
        val req = Request[IO](method = Method.GET, uri = uri"/health")
        app.run(req).use { resp =>
          assertEquals(resp.status, Status.Ok)
        }
      }

      runTest("POST /tasks returns 201 and id 1") { app =>
        val body = """{"title":"Test Task"}"""
        val req = Request[IO](
          method = Method.POST,
          uri = uri"/tasks",
          entity = body
        )
        app.run(req).use { resp =>
          assertEquals(resp.status, Status.Created)
          val task = resp.as[Task].unsafeRunSync()
          assertEquals(task.id, 1L)
          assertEquals(task.title, "Test Task")
          assertEquals(task.done, false)
        }
      }

      runTest("GET /tasks/1 after creation returns the task") { app =>
        val createReq = Request[IO](
          method = Method.POST,
          uri = uri"/tasks",
          entity = """{"title":"Created"}"""
        )
        for {
          _ <- app.run(createReq).use(_ => IO.unit)
          getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
          resp <- app.run(getReq)
          task <- resp.as[Task]
        } yield {
          assertEquals(resp.status, Status.Ok)
          assertEquals(task.title, "Created")
        }
      }

      runTest("GET /tasks/999 returns 404") { app =>
        val req = Request[IO](method = Method.GET, uri = uri"/tasks/999")
        app.run(req).use { resp =>
          assertEquals(resp.status, Status.NotFound)
        }
      }

      runTest("DELETE existing task returns 204, subsequent GET returns 404") { app =>
        val createReq = Request[IO](
          method = Method.POST,
          uri = uri"/tasks",
          entity = """{"title":"To Delete"}"""
        )
        for {
          _ <- app.run(createReq).use(_ => IO.unit)
          deleteReq = Request[IO](method = Method.DELETE, uri = uri"/tasks/1")
          delResp <- app.run(deleteReq)
          _ <- IO(assertEquals(delResp.status, Status.NoContent))
          getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
          getResp <- app.run(getReq)
          _ <- IO(assertEquals(getResp.status, Status.NotFound))
        } yield ()
      }
    }
    ```

    Wait, `App.HttpApp[IO]` is not correct. `HttpApp[IO]` is from http4s. I should just use `HttpApp[IO]`.
    Also, `resp.as[Task]` requires an implicit `EntityDecoder[IO, Task]`. With `http4s-circe` and `circe-generic`, it should be available via `io.circe.syntax._` and `org.http4s.circe._`. I need to import `org.http4s.circe._` in tests.
    Also, `uri"/health"` requires `org.http4s.implicits._`.
    Let's adjust imports in test file:
    ```scala
    import cats.effect.IO
    import cats.effect.unsafe.implicits.global
    import munit.FunSuite
    import org.http4s._
    import org.http4s.dsl.io._
    import org.http4s.implicits._
    import org.http4s.circe._
    import io.circe.generic.auto._
    import io.circe.syntax._
    ```

    One thing: `App.freshApp` returns `IO[HttpApp[IO]]`. In tests, I'll call `App.freshApp.flatMap(app => ...)`.
    The test structure is fine.

    Let's verify `runTest` helper: It's a bit unorthodox but works. Alternatively, I can just write standard munit tests:
    ```scala
    class ApiTests extends FunSuite {
      test("GET /health returns 200") {
        App.freshApp.flatMap { app =>
          val req = Request[IO](method = Method.GET, uri = uri"/health")
          app.run(req).use { resp =>
            IO(assertEquals(resp.status, Status.Ok))
          }
        }.unsafeRunSync()
      }
      // ...
    }
    ```
    This is cleaner. I'll stick with direct tests.

    Check `resp.as[Task]`: It returns `IO[Task]`. In a test, calling `.unsafeRunSync()` inside the `use` block or after is fine since we're in a test context. But to be safe and idiomatic, I'll keep it inside `IO` or use `IO.pure` with assertions. Munit allows synchronous assertions in tests, but since we're driving `IO`, we'll just call `.unsafeRunSync()` at the end of the `IO` chain, or use `IO(assertEquals(...))`.

    Let's rewrite tests cleanly:

    ```scala
    import cats.effect.IO
    import cats.effect.unsafe.implicits.global
    import munit.FunSuite
    import org.http4s._
    import org.http4s.dsl.io._
    import org.http4s.implicits._
    import org.http4s.circe._
    import io.circe.generic.auto._
    import io.circe.syntax._

    class ApiTests extends FunSuite {

      test("GET /health returns 200") {
        App.freshApp.flatMap { app =>
          val req = Request[IO](method = Method.GET, uri = uri"/health")
          app.run(req).use { resp =>
            IO(assertEquals(resp.status, Status.Ok))
          }
        }.unsafeRunSync()
      }

      test("POST /tasks returns 201 and id 1") {
        App.freshApp.flatMap { app =>
          val req = Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            entity = """{"title":"Test Task"}"""
          )
          app.run(req).use { resp =>
            for {
              _ <- IO(assertEquals(resp.status, Status.Created))
              task <- resp.as[Task]
              _ <- IO(assertEquals(task.id, 1L))
              _ <- IO(assertEquals(task.title, "Test Task"))
              _ <- IO(assertEquals(task.done, false))
            } yield ()
          }
        }.unsafeRunSync()
      }

      test("GET /tasks/1 after creation returns the task") {
        App.freshApp.flatMap { app =>
          val createReq = Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            entity = """{"title":"Created"}"""
          )
          for {
            _ <- app.run(createReq).use(_ => IO.unit)
            getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
            resp <- app.run(getReq)
            task <- resp.as[Task]
            _ <- IO(assertEquals(resp.status, Status.Ok))
            _ <- IO(assertEquals(task.title, "Created"))
          } yield ()
        }.unsafeRunSync()
      }

      test("GET /tasks/999 returns 404") {
        App.freshApp.flatMap { app =>
          val req = Request[IO](method = Method.GET, uri = uri"/tasks/999")
          app.run(req).use { resp =>
            IO(assertEquals(resp.status, Status.NotFound))
          }
        }.unsafeRunSync()
      }

      test("DELETE existing task returns 204, subsequent GET returns 404") {
        App.freshApp.flatMap { app =>
          val createReq = Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            entity = """{"title":"To Delete"}"""
          )
          for {
            _ <- app.run(createReq).use(_ => IO.unit)
            deleteReq = Request[IO](method = Method.DELETE, uri = uri"/tasks/1")
            delResp <- app.run(deleteReq)
            _ <- IO(assertEquals(delResp.status, Status.NoContent))
            getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
            getResp <- app.run(getReq)
            _ <- IO(assertEquals(getResp.status, Status.NotFound))
          } yield ()
        }.unsafeRunSync()
      }
    }
    ```

    Check constraints:
    - "Drive the HttpApp[IO] directly by constructing Request[IO] values and calling .run(...) — do not bind a real TCP port." -> Yes.
    - Covers all 5 cases. -> Yes.
    - Uses munit. -> Yes.
    - Imports match. -> Yes.

    One detail: `App.freshApp` is in `object App`. In tests, I refer to `App.freshApp`. That's fine.
    Should I make `App` package-private or public? Public is fine.
    I'll put `App` object in `main.scala`. The tests will access it.

    Let's verify http4s DSL syntax for path segments:
    `path("tasks" / Long)` is correct for 0.23.x.
    `uri"/health"` requires `org.http4s.implicits._`.
    `Request[IO](entity = "...")` works because of `EntityEncoder.stringEncoder[IO]` from implicits.

    One potential issue: `io.circe.generic.auto._` might not work seamlessly with Scala 3 in some setups without `scala-compiler` or specific macro setup, but http4s examples often use it. To be strictly safe, I could define explicit codecs, but the prompt says "e.g. io.circe.generic.auto._ or explicit Encoder/Decoder". I'll stick with `auto._` as it's standard for these quick setups. If needed, I can add explicit ones, but it's probably fine. Actually, for Scala 3, `io.circe.generic.auto._` uses macros that work. I'll keep it.

    Wait, `org.http4s.circe._` import in tests is needed for `resp.as[Task]`. I'll add it.

    Let's double check the `routes` function signature requirement:
    "Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`"
    My code: `def routes(store: Ref[IO, TaskStore], counter: Ref[IO, Long]): HttpRoutes[IO]`
    Matches.

    "plus a helper `def freshApp: IO[HttpApp[IO]]` that allocates new state and returns the routes as an HttpApp"
    My code: `def freshApp: IO[HttpApp[IO]] = ...`
    Matches.

    Check `Main` object:
    "Provide an `object Main extends IOApp.Simple` that serves on port 3000 via Ember."
    My code matches.

    One minor thing: `store.updateAndGet(_. - id)` might be slightly inefficient but correct. Alternatively:
    ```scala
    val delete = DELETE(path("tasks" / Long)) { id =>
      store.get.flatMap { tasks =>
        tasks.get(id).map { _ =>
          store.update(_. - id).as(NoContent())
        }.getOrElse(NotFound())
      }
    }
    ```
    This avoids `updateAndGet` and is cleaner. I'll use this.

    Let's refine `main.scala` slightly for robustness:
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
    import org.http4s.ember.server.EmberServerBuilder
    import io.circe._
    import io.circe.generic.auto._
    import io.circe.syntax._

    case class Task(id: Long, title: String, done: Boolean)
    case class CreateTask(title: String)
    case class UpdateTask(title: String, done: Boolean)

    object App {
      type TaskStore = Map[Long, Task]

      def routes(store: Ref[IO, TaskStore], counter: Ref[IO, Long]): HttpRoutes[IO] = {
        val health = GET(path("health")) {
          Ok(Json.obj("status" -> Json.fromString("ok")))
        }

        val getAll = GET(path("tasks")) {
          store.get.map { tasks =>
            Ok(tasks.values.toList.sortBy(_.id).asJson)
          }
        }

        val create = POST(path("tasks")) {
          as[CreateTask].flatMap { input =>
            counter.update(_ + 1).flatMap { id =>
              val task = Task(id, input.title, done = false)
              store.update(_.updated(id, task)).as(Created(task))
            }
          }
        }

        val getById = GET(path("tasks" / Long)) { id =>
          store.get.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task)
              case None => NotFound()
            }
          }
        }

        val update = PUT(path("tasks" / Long)) { id =>
          as[UpdateTask].flatMap { input =>
            store.get.flatMap { tasks =>
              tasks.get(id) match {
                case Some(existing) =>
                  val updated = existing.copy(title = input.title, done = input.done)
                  store.update(_.updated(id, updated)).as(Ok(updated))
                case None => NotFound()
              }
            }
          }
        }

        val delete = DELETE(path("tasks" / Long)) { id =>
          store.get.flatMap { tasks =>
            tasks.get(id) match {
              case Some(_) =>
                store.update(_. - id).as(NoContent())
              case None =>
                NotFound()
            }
          }
        }

        health ++ getAll ++ create ++ getById ++ update ++ delete
      }

      def freshApp: IO[HttpApp[IO]] = {
        for {
          store <- Ref.of[IO, TaskStore](Map.empty)
          counter <- Ref.of[IO, Long](0L)
        } yield routes(store, counter).orNotFound
      }
    }

    object Main extends IOApp.Simple {
      val run: IO[ExitCode] = App.freshApp.flatMap { app =>
        EmberServerBuilder
          .default[IO]
          .withHost("0.0.0.0")
          .withPort(3000)
          .withHttpApp(app)
          .build
          .use(_ => IO.never)
          .as(ExitCode.Success)
      }
    }
    ```

    Check test file imports and structure:
    ```scala
    import cats.effect.IO
    import cats.effect.unsafe.implicits.global
    import munit.FunSuite
    import org.http4s._
    import org.http4s.dsl.io._
    import org.http4s.implicits._
    import org.http4s.circe._
    import io.circe.generic.auto._
    import io.circe.syntax._

    class ApiTests extends FunSuite {

      test("GET /health returns 200") {
        App.freshApp.flatMap { app =>
          val req = Request[IO](method = Method.GET, uri = uri"/health")
          app.run(req).use { resp =>
            IO(assertEquals(resp.status, Status.Ok))
          }
        }.unsafeRunSync()
      }

      test("POST /tasks returns 201 and id 1") {
        App.freshApp.flatMap { app =>
          val req = Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            entity = """{"title":"Test Task"}"""
          )
          app.run(req).use { resp =>
            for {
              _ <- IO(assertEquals(resp.status, Status.Created))
              task <- resp.as[Task]
              _ <- IO(assertEquals(task.id, 1L))
              _ <- IO(assertEquals(task.title, "Test Task"))
              _ <- IO(assertEquals(task.done, false))
            } yield ()
          }
        }.unsafeRunSync()
      }

      test("GET /tasks/1 after creation returns the task") {
        App.freshApp.flatMap { app =>
          val createReq = Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            entity = """{"title":"Created"}"""
          )
          for {
            _ <- app.run(createReq).use(_ => IO.unit)
            getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
            resp <- app.run(getReq)
            task <- resp.as[Task]
            _ <- IO(assertEquals(resp.status, Status.Ok))
            _ <- IO(assertEquals(task.title, "Created"))
          } yield ()
        }.unsafeRunSync()
      }

      test("GET /tasks/999 returns 404") {
        App.freshApp.flatMap { app =>
          val req = Request[IO](method = Method.GET, uri = uri"/tasks/999")
          app.run(req).use { resp =>
            IO(assertEquals(resp.status, Status.NotFound))
          }
        }.unsafeRunSync()
      }

      test("DELETE existing task returns 204, subsequent GET returns 404") {
        App.freshApp.flatMap { app =>
          val createReq = Request[IO](
            method = Method.POST,
            uri = uri"/tasks",
            entity = """{"title":"To Delete"}"""
          )
          for {
            _ <- app.run(createReq).use(_ => IO.unit)
            deleteReq = Request[IO](method = Method.DELETE, uri = uri"/tasks/1")
            delResp <- app.run(deleteReq)
            _ <- IO(assertEquals(delResp.status, Status.NoContent))
            getReq = Request[IO](method = Method.GET, uri = uri"/tasks/1")
            getResp <- app.run(getReq)
            _ <- IO(assertEquals(getResp.status, Status.NotFound))
          } yield ()
        }.unsafeRunSync()
      }
    }
    ```

    Everything looks solid. I will verify one last time:
    - Dependencies exactly match prompt.
    - Data model matches.
    - Endpoints match.
    - `Ref` used.
    - `routes` and `freshApp` exposed.
    - `Main` serves on 3000.
    - Tests use munit, drive `HttpApp` directly, cover 5 cases.
    - Output format matches requirement.

    One minor point: `io.circe.generic.auto._` might need `io.circe.parser._` or similar? No, `as[T]` uses `EntityDecoder` which uses `Decoder[T]`. `generic.auto._` provides `Decoder[T]` and `Encoder[T]`. It's fine.
    Also, `org.http4s.circe._` provides the `EntityEncoder`/`EntityDecoder` bridges.
    All good.

    I will generate the output exactly as requested.
    Format:
    ### FILE: main.scala
    ```scala
    ...
    ```
    ### FILE: main.test.scala
    ```scala
    ...
    ```
    No extra text.
    Ready. 
    One check: `uri"/health"` requires `org.http4s.implicits._` which is imported.
    `Request[IO](entity = "...")` uses `EntityEncoder.stringEncoder[IO]` from implicits.
    All correct.
    Proceed. 
    Output matches response. 
    Self-Correction/Refinement during thought: 
    I should ensure `App` object is accessible from tests. Since both are in the same project root, it's fine.
    I'll make sure the code compiles conceptually. 
    `store.get.map { tasks => Ok(tasks.values.toList.sortBy(_.id).asJson) }` -> `tasks.values.toList` is fine.
    `counter.update(_ + 1).flatMap { id => ... }` -> `update` returns `IO[Unit]`, so `flatMap` won't give `id`. Oops! 
    Fix: `counter.updateAndGet(_ + 1).flatMap { id => ... }` or `counter.get.flatMap(c => counter.update(_ + 1).as(c + 1))`. `updateAndGet` is cleaner.
    Let's fix that:
    ```scala
    val create = POST(path("tasks")) {
      as[CreateTask].flatMap { input =>
        counter.updateAndGet(_ + 1).flatMap { id =>
          val task = Task(id, input.title, done = false)
          store.update(_.updated(id, task)).as(Created(task))
        }
      }
    }
    ```
    This is correct. I missed that in the draft. Good catch.

    Also, `store.updateAndGet(_. - id)` in delete was replaced with `get` + `update`, so that's fine.

    Final check of the code:
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
    import org.http4s.ember.server.EmberServerBuilder
    import io.circe._
    import io.circe.generic.auto._
    import io.circe.syntax._

    case class Task(id: Long, title: String, done: Boolean)
    case class CreateTask(title: String)
    case class UpdateTask(title: String, done: Boolean)

    object App {
      type TaskStore = Map[Long, Task]

      def routes(store: Ref[IO, TaskStore], counter: Ref[IO, Long]): HttpRoutes[IO] = {
        val health = GET(path("health")) {
          Ok(Json.obj("status" -> Json.fromString("ok")))
        }

        val getAll = GET(path("tasks")) {
          store.get.map { tasks =>
            Ok(tasks.values.toList.sortBy(_.id).asJson)
          }
        }

        val create = POST(path("tasks")) {
          as[CreateTask].flatMap { input =>
            counter.updateAndGet(_ + 1).flatMap { id =>
              val task = Task(id, input.title, done = false)
              store.update(_.updated(id, task)).as(Created(task))
            }
          }
        }

        val getById = GET(path("tasks" / Long)) { id =>
          store.get.flatMap { tasks =>
            tasks.get(id) match {
              case Some(task) => Ok(task)
              case None => NotFound()
            }
          }
        }

        val update = PUT(path("tasks" / Long)) { id =>
          as[UpdateTask].flatMap { input =>
            store.get.flatMap { tasks =>
              tasks.get(id) match {
                case Some(existing) =>
                  val updated = existing.copy(title = input.title, done = input.done)
                  store.update(_.updated(id, updated)).as(Ok(updated))
                case None => NotFound()
              }
            }
          }
        }

        val delete = DELETE(path("tasks" / Long)) { id =>
          store.get.flatMap { tasks =>
            tasks.get(id) match {
              case Some(_) =>
                store.update(_. - id).as(NoContent())
              case None =>
                NotFound()
            }
          }
        }

        health ++ getAll ++ create ++ getById ++ update ++ delete
      }

      def freshApp: IO[HttpApp[IO]] = {
        for {
          store <- Ref.of[IO, TaskStore](Map.empty)
          counter <- Ref.of[IO, Long](0L)
        } yield routes(store, counter).orNotFound
      }
    }

    object Main extends IOApp.Simple {
      val run: IO[ExitCode] = App.freshApp.flatMap { app =>
        EmberServerBuilder
          .default[IO]
          .withHost("0.0.0.0")
          .withPort(3000)
          .withHttpApp(app)
          .build
          .use(_ => IO.never)
          .as(ExitCode.Success)
      }
    }
    ```
    This looks solid. I will produce the output now. 
    One detail: `io.circe.generic.auto._` might conflict with Scala 3's macro system if not careful, but it's standard. I'll keep it. If needed, explicit codecs are safer, but the prompt allows auto. I'll stick with auto.
    All constraints met. Output matches format.
    Proceed. 
    [Output Generation] -> matches the final code. 
    Self-Correction/Note during generation prep: 
    I should ensure `org.http4s.circe._` is imported in tests. It is.
    I should ensure `cats.effect.unsafe.implicits.global` is imported in tests. It is.
    Everything is ready. 
    Output matches exactly. 
    Proceeds. 
    [Done]
