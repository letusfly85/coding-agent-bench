Here's a thinking process:

1.  **Understand the Goal**: The user wants a small REST API in Scala 3 using http4s, built with scala-cli. The requirements are specific about dependencies, data model, endpoints, implementation constraints, and tests. Output should be exactly two files: `main.scala` and `main.test.scala`, with no extra commentary.

2.  **Analyze Requirements**:
   - **Scala 3 & scala-cli**: Use `//> using` directives at the top of `main.scala`.
   - **Dependencies**: Exactly as specified.
   - **Data Model**: `case class Task(id: Long, title: String, done: Boolean)`
   - **Endpoints**:
     - `GET /health` -> 200 `{"status":"ok"}`
     - `GET /tasks` -> 200 JSON array of tasks (ascending id)
     - `POST /tasks` -> body `{"title":"..."}` -> 201 with created task (`done=false`, auto-increment id)
     - `GET /tasks/{id}` -> 200 or 404
     - `PUT /tasks/{id}` -> body `{"title":"...","done":true}` -> 200 or 404
     - `DELETE /tasks/{id}` -> 204 or 404
   - **Implementation Constraints**:
     - Use `cats.effect.IO`
     - Use `HttpRoutes[IO]`
     - In-memory state with `Ref[IO, ...]`
     - Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`
     - Expose `def freshApp: IO[HttpApp[IO]]`
     - Derive circe codecs for `Task`
     - `object Main extends IOApp.Simple` serving on port 3000 via Ember
   - **Tests**:
     - File: `main.test.scala`
     - Use munit
     - Drive `HttpApp[IO]` directly with `Request[IO]`
     - Cover specific cases (health, post, get, 404, delete)
     - No real TCP port

3.  **Design `main.scala`**:
   - Start with scala-cli directives.
   - Import necessary libraries: http4s, cats-effect, circe.
   - Define `Task` case class with circe codecs.
   - Define state: `Ref[IO, Map[Long, Task]]` for store, `Ref[IO, Long]` for counter.
   - Implement `routes(store, counter)`:
     - Use `HttpRoutes.of[IO]` or `HttpApp.of[IO]`? The requirement says `HttpRoutes[IO]`, but we can use `HttpRoutes.of` and later `.orNotFound` or `.toHttpApp`.
     - Actually, http4s DSL provides `HttpRoutes.of[IO] { case ... => ... }`
     - Let's map endpoints:
       - `GET /health` -> `Ok(json"""{"status":"ok"}""")` or use circe for a simple case class, or just `Response[IO](Status.Ok).withEntity("""{"status":"ok"}""")`. Simpler: `Ok("""{"status":"ok"}""")`
       - `GET /tasks` -> read store, sort by id, return as JSON array.
       - `POST /tasks` -> parse `{"title":"..."}`, get next id, create task, update store and counter, return 201.
       - `GET /tasks/{id}` -> find task, 200 or 404.
       - `PUT /tasks/{id}` -> parse `{"title":"...","done":true}`, update if exists, 200 or 404.
       - `DELETE /tasks/{id}` -> remove if exists, 204 or 404.
   - Implement `freshApp`:
     - Create new `Ref` for store (empty map) and counter (0L).
     - Call `routes(store, counter)`.
     - Convert to `HttpApp[IO]` using `.orNotFound` or `.toHttpApp`.
   - Implement `Main`:
     - Use `EmberServerBuilder[IO]` to serve on 3000.
     - Use `freshApp` to get the app.

   Let's draft the code mentally/refine:
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
   import org.http4s.circe._
   import org.http4s.implicits._
   import io.circe.generic.auto._
   import io.circe.Encoder
   import scala.concurrent.duration._

   case class Task(id: Long, title: String, done: Boolean)

   object App {
     def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
       HttpRoutes.of[IO] {
         case req @ GET -> Root / "health" =>
           Ok("""{"status":"ok"}""")

         case GET -> Root / "tasks" =>
           store.get.map(_.values.toSeq.sortBy(_.id)).flatMap { tasks =>
             Ok(tasks)
           }

         case req @ POST -> Root / "tasks" =>
           req.as[TaskCreateRequest].flatMap { input =>
             for {
               nextId <- counter.updateAndGet(_ + 1)
               task = Task(nextId, input.title, false)
               _ <- store.update(_.updated(nextId, task))
             } yield Created(task)
           }

         case GET -> Root / "tasks" / LongVar(id) =>
           store.get.flatMap { tasks =>
             tasks.get(id) match {
               case Some(task) => Ok(task)
               case None => NotFound()
             }
           }

         case req @ PUT -> Root / "tasks" / LongVar(id) =>
           req.as[TaskUpdateRequest].flatMap { input =>
             store.get.flatMap { tasks =>
               tasks.get(id) match {
                 case Some(existing) =>
                   val updated = existing.copy(title = input.title, done = input.done)
                   store.update(_.updated(id, updated)).as(Ok(updated))
                 case None => NotFound()
               }
             }
           }

         case req @ DELETE -> Root / "tasks" / LongVar(id) =>
           store.get.flatMap { tasks =>
             tasks.get(id) match {
               case Some(_) =>
                 store.update(_. - id).as(NoContent())
               case None => NotFound()
             }
           }
       }
     }

     def freshApp: IO[HttpApp[IO]] = for {
       store <- Ref.of[IO, Map[Long, Task]](Map.empty)
       counter <- Ref.of[IO, Long](0L)
       routes = routes(store, counter)
       app = routes.orNotFound
     } yield app
   }

   // Helper case classes for request bodies
   case class TaskCreateRequest(title: String)
   case class TaskUpdateRequest(title: String, done: Boolean)

   object Main extends IOApp.Simple {
     val run = org.http4s.ember.server.EmberServerBuilder
       .default[IO]
       .withPort(3000)
       .withHttpApp(App.freshApp.flatMap(identity))
       .build
       .useForever
   }
   ```

   Wait, `App.freshApp.flatMap(identity)` is wrong. `freshApp` returns `IO[HttpApp[IO]]`. `withHttpApp` expects `HttpApp[IO]`. I should just call `App.freshApp.flatMap(app => EmberServerBuilder.default[IO].withHttpApp(app).build.useForever)` or better:
   ```scala
   object Main extends IOApp.Simple {
     val run = App.freshApp.flatMap { app =>
       org.http4s.ember.server.EmberServerBuilder
         .default[IO]
         .withPort(3000)
         .withHttpApp(app)
         .build
         .useForever
     }
   }
   ```

   Let's check imports and details:
   - `org.http4s.dsl.io._` provides `GET`, `POST`, `PUT`, `DELETE`, `Root`, `/`, `Ok`, `Created`, `NotFound`, `NoContent`.
   - `req.as[T]` is from `org.http4s.circe._` or `org.http4s.syntax.all._`. Actually, `req.as[T]` is in `org.http4s.circe.CirceInstances` or similar. In http4s 0.23, it's `req.as[T]` via `EntityDecoder`. We need `import org.http4s.circe._` which brings in circe instances.
   - `HttpRoutes.of[IO]` is correct.
   - `routes.orNotFound` converts `HttpRoutes[IO]` to `HttpApp[IO]`.
   - Circe codecs: `io.circe.generic.auto._` will derive for `Task`, `TaskCreateRequest`, `TaskUpdateRequest`.
   - Sorting tasks: `tasks.values.toSeq.sortBy(_.id)` works.
   - `LongVar(id)` is from `org.http4s.dsl.io._`.
   - Check `POST /tasks` body: requirement says `{"title":"..."}`. My `TaskCreateRequest` matches.
   - Check `PUT /tasks/{id}` body: `{"title":"...","done":true}`. My `TaskUpdateRequest` matches.
   - Check `GET /health`: returns `{"status":"ok"}`. I used a raw string. That's fine. Could also use a case class, but raw is simpler and matches requirement exactly.
   - Check `DELETE /tasks/{id}`: returns 204 if deleted, 404 if absent. Implemented.
   - Check `GET /tasks`: returns JSON array. `Ok(tasks)` where `tasks` is `Seq[Task]` will use circe to serialize as array.
   - Check constraints: "Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`" - done.
   - "plus a helper `def freshApp: IO[HttpApp[IO]]`" - done.
   - "Provide an `object Main extends IOApp.Simple` that serves on port 3000 via Ember." - done.

   One minor thing: `org.http4s.implicits._` is needed for `LongVar`, `Root`, etc. Actually, `org.http4s.dsl.io._` includes them in 0.23? Let's verify. In http4s 0.23, `org.http4s.dsl.io._` provides `GET`, `POST`, etc., and `Root`, `/`, `LongVar`. `org.http4s.implicits._` is often used for `Request`/`Response` syntax, but `dsl.io._` usually covers routing syntax. To be safe, I'll import `org.http4s.implicits._`.

   Let's refine imports:
   ```scala
   import cats.effect.{IO, IOApp, Ref}
   import cats.syntax.all._
   import org.http4s._
   import org.http4s.dsl.io._
   import org.http4s.circe._
   import org.http4s.implicits._
   import io.circe.generic.auto._
   ```

   Check `req.as[T]`: In http4s 0.23, `req.as[T]` is available via `EntityDecoder` instances. `org.http4s.circe._` provides `CirceInstances` which brings them in. Actually, `req.as[T]` is an extension method on `Request` in `org.http4s.circe._` or `org.http4s.syntax.all._`. I'll add `import org.http4s.syntax.all._` just in case, though `cats.syntax.all._` might not cover it. Actually, `org.http4s.circe._` is enough.

   Let's test mentally:
   `HttpRoutes.of[IO] { case GET -> Root / "health" => Ok("""{"status":"ok"}""") }`
   This is valid.

   One detail: `App.freshApp` should be in the same file. I'll put it in an `object Api` or just at top level, or inside `App`. The requirement says "Expose `def routes(...)`", so I'll put them in an object `Api`.

   Let's structure `main.scala`:
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
   import org.http4s.circe._
   import org.http4s.implicits._
   import io.circe.generic.auto._

   case class Task(id: Long, title: String, done: Boolean)
   case class TaskCreateRequest(title: String)
   case class TaskUpdateRequest(title: String, done: Boolean)

   object Api {
     def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] =
       HttpRoutes.of[IO] {
         case GET -> Root / "health" =>
           Ok("""{"status":"ok"}""")

         case GET -> Root / "tasks" =>
           store.get.map(_.values.toSeq.sortBy(_.id)).flatMap(Ok(_))

         case req @ POST -> Root / "tasks" =>
           req.as[TaskCreateRequest].flatMap { input =>
             for {
               nextId <- counter.updateAndGet(_ + 1)
               task = Task(nextId, input.title, false)
               _ <- store.update(_.updated(nextId, task))
             } yield Created(task)
           }

         case GET -> Root / "tasks" / LongVar(id) =>
           store.get.flatMap { tasks =>
             tasks.get(id) match {
               case Some(t) => Ok(t)
               case None    => NotFound()
             }
           }

         case req @ PUT -> Root / "tasks" / LongVar(id) =>
           req.as[TaskUpdateRequest].flatMap { input =>
             store.get.flatMap { tasks =>
               tasks.get(id) match {
                 case Some(existing) =>
                   val updated = existing.copy(title = input.title, done = input.done)
                   store.update(_.updated(id, updated)).as(Ok(updated))
                 case None => NotFound()
               }
             }
           }

         case DELETE -> Root / "tasks" / LongVar(id) =>
           store.get.flatMap { tasks =>
             tasks.get(id) match {
               case Some(_) => store.update(_. - id).as(NoContent())
               case None    => NotFound()
             }
           }
       }

     def freshApp: IO[HttpApp[IO]] = for {
       store <- Ref.of[IO, Map[Long, Task]](Map.empty)
       counter <- Ref.of[IO, Long](0L)
       app = routes(store, counter).orNotFound
     } yield app
   }

   object Main extends IOApp.Simple {
     val run = Api.freshApp.flatMap { app =>
       org.http4s.ember.server.EmberServerBuilder
         .default[IO]
         .withPort(3000)
         .withHttpApp(app)
         .build
         .useForever
     }
   }
   ```

   This looks solid. Let's verify constraints:
   - "Use cats.effect.IO and http4s' HttpRoutes." -> Yes.
   - "In-memory state only — use cats.effect.Ref for the store." -> Yes.
   - "Expose def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]" -> Yes.
   - "plus a helper def freshApp: IO[HttpApp[IO]]" -> Yes.
   - "Derive circe codecs for Task" -> Yes, via `io.circe.generic.auto._`.
   - "Provide an object Main extends IOApp.Simple that serves on port 3000 via Ember." -> Yes.

   Now `main.test.scala`:
   Requirements:
   - Use munit.
   - Drive `HttpApp[IO]` directly by constructing `Request[IO]` values and calling `.run(...)`.
   - Do not bind a real TCP port.
   - Cover:
     1. GET /health returns 200
     2. POST /tasks returns 201 and id 1
     3. GET /tasks/1 after creation returns the task
     4. GET /tasks/999 returns 404
     5. DELETE an existing task returns 204, and a subsequent GET returns 404

   I'll write tests using `munit.CatsEffectSuite` or just `munit.FunSuite` with `IO` blocking in tests? Better to use `munit.CatsEffectSuite` for idiomatic handling, but standard `munit.FunSuite` with `IO.unsafeRunSync()` is also fine. The prompt says "using munit", so I'll stick to `munit.FunSuite` and use `IO.unsafeRunSync()` or `cats.effect.unsafe.implicits.global.runSync`. Actually, `munit.CatsEffectSuite` is cleaner. I'll use `munit.FunSuite` with `IO.unsafeRunSync()` for simplicity, or just `munit.CatsEffectSuite`. Let's use `munit.CatsEffectSuite` as it's standard for Cats Effect + MUnit.

   Wait, `munit.CatsEffectSuite` requires `org.typelevel::munit-cats-effect-3`. The prompt only specifies `org.scalameta::munit::1.0.4`. So I should stick to standard MUnit and run IOs manually, or just use `IO.unsafeRunSync()` in `munit.FunSuite`.

   Let's use `munit.FunSuite`:
   ```scala
   import munit.FunSuite
   import cats.effect.IO
   import org.http4s._
   import org.http4s.dsl.io._
   import org.http4s.circe._
   import org.http4s.implicits._
   import io.circe.generic.auto._
   import io.circe.parser.decode

   class ApiTests extends FunSuite {

     def runTest(name: String)(test: IO[Unit]) = {
       test(name) {
         IO.unsafeRunSync(test)
       }
     }

     test("GET /health returns 200") {
       val app = IO.unsafeRunSync(Api.freshApp)
       val req = Request[IO](GET, uri"/health")
       val resp = IO.unsafeRunSync(app.run(req))
       assertEquals(resp.status, Status.Ok)
       val body = IO.unsafeRunSync(resp.bodyText.compile.string)
       assertEquals(body, """{"status":"ok"}""")
     }

     test("POST /tasks returns 201 and id 1") {
       val app = IO.unsafeRunSync(Api.freshApp)
       val req = Request[IO](Method.POST, uri"/tasks")
         .withEntity("""{"title":"Test"}""")
       val resp = IO.unsafeRunSync(app.run(req))
       assertEquals(resp.status, Status.Created)
       val task = IO.unsafeRunSync(decode[Task](IO.unsafeRunSync(resp.bodyText.compile.string)))
       assertEquals(task.id, 1L)
     }

     test("GET /tasks/1 after creation returns the task") {
       val app = IO.unsafeRunSync(Api.freshApp)
       val createReq = Request[IO](Method.POST, uri"/tasks").withEntity("""{"title":"First"}""")
       IO.unsafeRunSync(app.run(createReq))

       val getReq = Request[IO](GET, uri"/tasks/1")
       val resp = IO.unsafeRunSync(app.run(getReq))
       assertEquals(resp.status, Status.Ok)
       val task = IO.unsafeRunSync(decode[Task](IO.unsafeRunSync(resp.bodyText.compile.string)))
       assertEquals(task.title, "First")
     }

     test("GET /tasks/999 returns 404") {
       val app = IO.unsafeRunSync(Api.freshApp)
       val req = Request[IO](GET, uri"/tasks/999")
       val resp = IO.unsafeRunSync(app.run(req))
       assertEquals(resp.status, Status.NotFound)
     }

     test("DELETE existing task returns 204, subsequent GET returns 404") {
       val app = IO.unsafeRunSync(Api.freshApp)
       val createReq = Request[IO](Method.POST, uri"/tasks").withEntity("""{"title":"ToDelete"}""")
       IO.unsafeRunSync(app.run(createReq))

       val deleteReq = Request[IO](Method.DELETE, uri"/tasks/1")
       val deleteResp = IO.unsafeRunSync(app.run(deleteReq))
       assertEquals(deleteResp.status, Status.NoContent)

       val getReq = Request[IO](GET, uri"/tasks/1")
       val getResp = IO.unsafeRunSync(app.run(getReq))
       assertEquals(getResp.status, Status.NotFound)
     }
   }
   ```

   Wait, `IO.unsafeRunSync()` is deprecated in newer Cats Effect, but for 3.3.4 and CE 3, it's still available via `cats.effect.unsafe.implicits.global` or just `IO.unsafeRunSync()` if imported. Actually, in CE 3, `IO.unsafeRunSync()` is available on `IO` directly. To be safe, I'll import `cats.effect.unsafe.implicits.global` or just use `IO.unsafeRunSync()`. I'll add `import cats.effect.unsafe.implicits.global` to be explicit, or just rely on `IO.unsafeRunSync()`.

   Actually, `munit.FunSuite` tests are synchronous. Using `IO.unsafeRunSync()` inside each test is fine.

   Let's adjust imports for test file:
   ```scala
   import munit.FunSuite
   import cats.effect.IO
   import cats.effect.unsafe.implicits.global
   import org.http4s._
   import org.http4s.dsl.io._
   import org.http4s.circe._
   import org.http4s.implicits._
   import io.circe.generic.auto._
   import io.circe.parser.decode
   ```

   One thing: `resp.bodyText.compile.string` requires `fs2` and `cats.effect.unsafe.implicits.global`. `compile` is from `fs2.Stream`. I'll import `fs2._` or `fs2.text._` if needed. Actually, `resp.bodyText` is a `Stream[IO, String]`. `.compile.string` is available via `fs2.text._` or just `fs2._`. I'll add `import fs2._`.

   Let's verify `resp.bodyText.compile.string` in http4s 0.23: Yes, it's standard.

   I'll refine the test file to be robust:
   ```scala
   import munit.FunSuite
   import cats.effect.IO
   import cats.effect.unsafe.implicits.global
   import org.http4s._
   import org.http4s.dsl.io._
   import org.http4s.circe._
   import org.http4s.implicits._
   import io.circe.generic.auto._
   import io.circe.parser.decode
   import fs2._

   class ApiTests extends FunSuite {

     test("GET /health returns 200") {
       val app = Api.freshApp.unsafeRunSync()
       val req = Request[IO](GET, uri"/health")
       val resp = app.run(req).unsafeRunSync()
       assertEquals(resp.status, Status.Ok)
       val body = resp.bodyText.compile.string.unsafeRunSync()
       assertEquals(body, """{"status":"ok"}""")
     }

     test("POST /tasks returns 201 and id 1") {
       val app = Api.freshApp.unsafeRunSync()
       val req = Request[IO](Method.POST, uri"/tasks")
         .withEntity("""{"title":"Test"}""")
       val resp = app.run(req).unsafeRunSync()
       assertEquals(resp.status, Status.Created)
       val task = decode[Task](resp.bodyText.compile.string.unsafeRunSync())
       assertEquals(task.right.get.id, 1L)
     }

     test("GET /tasks/1 after creation returns the task") {
       val app = Api.freshApp.unsafeRunSync()
       app.run(Request[IO](Method.POST, uri"/tasks").withEntity("""{"title":"First"}""")).unsafeRunSync()

       val resp = app.run(Request[IO](GET, uri"/tasks/1")).unsafeRunSync()
       assertEquals(resp.status, Status.Ok)
       val task = decode[Task](resp.bodyText.compile.string.unsafeRunSync())
       assertEquals(task.right.get.title, "First")
     }

     test("GET /tasks/999 returns 404") {
       val app = Api.freshApp.unsafeRunSync()
       val resp = app.run(Request[IO](GET, uri"/tasks/999")).unsafeRunSync()
       assertEquals(resp.status, Status.NotFound)
     }

     test("DELETE existing task returns 204, subsequent GET returns 404") {
       val app = Api.freshApp.unsafeRunSync()
       app.run(Request[IO](Method.POST, uri"/tasks").withEntity("""{"title":"ToDel"}""")).unsafeRunSync()

       val delResp = app.run(Request[IO](Method.DELETE, uri"/tasks/1")).unsafeRunSync()
       assertEquals(delResp.status, Status.NoContent)

       val getResp = app.run(Request[IO](GET, uri"/tasks/1")).unsafeRunSync()
       assertEquals(getResp.status, Status.NotFound)
     }
   }
   ```

   Check constraints: "Drive the HttpApp[IO] directly by constructing Request[IO] values and calling .run(...) — do not bind a real TCP port." -> Yes.
   "Cover at minimum: ..." -> All covered.
   Dependencies match exactly.

   One minor point: `Api.freshApp.unsafeRunSync()` is called multiple times. Each call creates a new isolated app, which is perfect for tests.

   Let's double check the `routes` signature requirement: "Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`". My code has exactly that.
   "plus a helper `def freshApp: IO[HttpApp[IO]]`" -> Yes.
   "Derive circe codecs for Task" -> `io.circe.generic.auto._` does it.
   "Provide an object Main extends IOApp.Simple that serves on port 3000 via Ember." -> Yes.

   One thing: `org.http4s.dsl.io._` might not export `Root` or `LongVar` in some versions. In 0.23, they are in `org.http4s.dsl.io._`. Actually, `Root` and `/` are in `org.http4s.dsl.io._`. `LongVar` is also there. To be absolutely safe, I'll import `org.http4s.dsl.io._` and `org.http4s.implicits._`. It's fine.

   Let's verify `HttpRoutes.of[IO]` syntax:
   ```scala
   HttpRoutes.of[IO] {
     case GET -> Root / "health" => ...
   }
   ```
   This is correct for http4s 0.23.

   Check `POST /tasks` body parsing: `req.as[TaskCreateRequest]` works if we have circe instances. `org.http4s.circe._` brings them in.

   Check `PUT /tasks/{id}`: `req.as[TaskUpdateRequest]` works.

   Check `DELETE /tasks/{id}`: `store.update(_. - id).as(NoContent())` works.

   All good.

   One detail: `IO.unsafeRunSync()` is deprecated in CE 3.4+, but the prompt specifies scala 3.3.4 and http4s 0.23.30, which pulls CE 3.5.x. `IO.unsafeRunSync()` is still available but marked deprecated in favor of `IOApp` or runtime. For tests, it's standard to use it or `cats.effect.unsafe.implicits.global`. I'll stick with `IO.unsafeRunSync()` as it's concise and widely used in examples. Alternatively, I can use `cats.effect.unsafe.implicits.global` and call `.unsafeRunSync()` on `IO`. It's fine.

   I will structure the output exactly as requested.
   Files:
   - `main.scala`
   - `main.test.scala`

   Final check of the code against requirements:
   - Dependencies: exact match.
   - Data model: exact.
   - Endpoints: all implemented.
   - Implementation constraints: `Ref`, `routes`, `freshApp`, `IO`, `HttpRoutes`, circe auto, `Main` on 3000.
   - Tests: munit, direct `HttpApp` drive, covers 5 cases.

   One small adjustment: In `main.scala`, I'll make sure `Api` object is public and matches the requirement wording. The requirement says "Expose def routes(...)" without specifying the object name, so `object Api` is fine. I'll put it at the top level.

   Let's produce the output. 
   I will ensure no extra text, just the file markers and code blocks.
   I'll double-check the `HttpRoutes.of` import. In http4s 0.23, `HttpRoutes.of` is in `org.http4s.HttpRoutes`. So `import org.http4s._` covers it.
   `GET`, `POST`, etc. are in `org.http4s.Method`, but `dsl.io._` provides pattern matchers.
   `Root`, `/` are in `org.http4s.dsl.io._`.
   `LongVar` is in `org.http4s.dsl.io._`.
   All good.

   One thing: `req.as[T]` might require `org.http4s.circe._` which I have.
   I'll write out the files now. 
   Self-Correction/Verification during thought:
   - `HttpRoutes.of[IO]` syntax is correct.
   - `routes.orNotFound` converts to `HttpApp[IO]`.
   - `EmberServerBuilder.default[IO]` is correct.
   - `App.freshApp` vs `Api.freshApp`: I'll use `object Api` to avoid conflict with `App` if any, but it's fine.
   - I'll make sure the test file imports match what's needed.
   - I'll use `cats.effect.IOApp` for Main.
   - All constraints met.

   Output matches request. Proceeds. 
   Generating. 
   [Output Generation] -> matches the final code. 
   Note: I'll ensure the code compiles conceptually. `IO.unsafeRunSync()` is fine. `fs2._` is needed for `compile.string`. `io.circe.parser.decode` is fine.
   All set. 
   Output matches exactly. 
   Proceeds. 
   [Done]
