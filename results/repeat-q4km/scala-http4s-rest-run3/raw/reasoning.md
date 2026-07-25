Here's a thinking process:

1.  **Understand the Requirements:**
   - Language: Scala 3
   - Build tool: scala-cli
   - Dependencies: Specific versions provided
   - Data model: `Task(id: Long, title: String, done: Boolean)`
   - State: In-memory, using `cats.effect.Ref`, ID starts at 1 and increments
   - Endpoints:
     - GET `/health` -> 200 `{"status":"ok"}`
     - GET `/tasks` -> 200 JSON array of all tasks (sorted by id)
     - POST `/tasks` -> 201 created task (`done=false`)
     - GET `/tasks/{id}` -> 200 task or 404
     - PUT `/tasks/{id}` -> 200 updated task or 404
     - DELETE `/tasks/{id}` -> 204 or 404
   - Implementation:
     - Use `cats.effect.IO` and `HttpRoutes[IO]`
     - Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`
     - Expose `def freshApp: IO[HttpApp[IO]]`
     - Derive circe codecs
     - `object Main extends IOApp.Simple` on port 3000 via Ember
   - Tests:
     - In `main.test.scala` using munit
     - Drive `HttpApp[IO]` directly (no TCP port)
     - Cover 5 specific scenarios
   - Output format: Strict, only files with `### FILE: <path>` and code blocks.

2.  **Design the Data Model & State:**
   - `Task` case class.
   - Store: `Ref[IO, Map[Long, Task]]`
   - Counter: `Ref[IO, Long]` (starts at 1)
   - Codecs: `io.circe.generic.auto._` is simple and fits.

3.  **Implement Routes:**
   - Need to import necessary http4s, cats, circe stuff.
   - Use `HttpRoutes.of[IO]` or route combinators.
   - Handle each endpoint:
     - `/health`: `Method.GET -> path("health") -> Ok(Response.json("""{"status":"ok"}"""))` or use circe.
     - `/tasks` GET: read map, sort by id, return as JSON.
     - `/tasks` POST: read counter, update counter, create task, update store, return 201.
     - `/tasks/:id` GET: find by id, return 200 or 404.
     - `/tasks/:id` PUT: find by id, update fields, update store, return 200 or 404.
     - `/tasks/:id` DELETE: find by id, remove, return 204 or 404.
   - Need to handle JSON parsing with `Request[IO].as[CreateTask]` and `Request[IO].as[UpdateTask]` or similar. Actually, for POST, body is `{"title":"..."}`. For PUT, `{"title":"...","done":true}`.
   - Let's define:
     - `case class CreateTask(title: String)`
     - `case class UpdateTask(title: String, done: Boolean)`
   - Or just decode directly from the request body. http4s-circe provides `as[T]`.

   Let's draft the routes function:
   ```scala
   import cats.effect.IO
   import cats.effect.kernel.Ref
   import cats.syntax.all._
   import org.http4s._
   import org.http4s.dsl.io._
   import org.http4s.circe._
   import io.circe.generic.auto._
   import scala.concurrent.duration._

   def routes(store: Ref[IO, Map[Long, Task]], counter: Ref[IO, Long]): HttpRoutes[IO] = {
     HttpRoutes.of[IO] {
       case req @ GET -> Root / "health" =>
         Ok("""{"status":"ok"}""")

       case GET -> Root / "tasks" =>
         store.get.map(_.values.toSeq.sortBy(_.id)).flatMap { tasks =>
           Ok(tasks)
         }

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
         store.updateAndGet(_. - id).flatMap { map =>
           if (map.contains(id)) NoContent() else NotFound()
         }
     }
   }
   ```

   Wait, `DELETE` logic: `store.updateAndGet(_. - id)` returns the map *before* deletion? Actually `updateAndGet` applies the function and returns the new state. Let's check:
   `updateAndGet` returns the new value. So if it contained `id`, the new map won't contain it. So:
   ```scala
   case DELETE -> Root / "tasks" / LongVar(id) =>
     store.updateAndGet(_. - id).flatMap { newMap =>
       if (!newMap.contains(id)) NoContent() else NotFound()
     }
   ```
   Actually, simpler: `getAndUpdate` returns old state.
   Let's use `getAndUpdate(_. - id)`:
   ```scala
   case DELETE -> Root / "tasks" / LongVar(id) =>
     store.getAndUpdate(_. - id).flatMap { oldMap =>
       if (oldMap.contains(id)) NoContent() else NotFound()
     }
   ```
   That's correct.

   For `POST`, need `CreateTask` case class. For `PUT`, `UpdateTask`.
   Let's define them at the top level or inside the file.

   Also, need to handle JSON decoding errors gracefully? The requirements don't specify, but http4s default behavior is fine, or we can catch `MalformedMessageBodyFailure`. I'll keep it simple.

   Let's refine `routes`:
   ```scala
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
   ```

   Wait, `HttpRoutes.of` pattern matching syntax in http4s 0.23 uses `dsl.io._` which provides `GET`, `POST`, etc. and `Root / "tasks"`.
   Let's verify imports:
   ```scala
   import cats.effect.IO
   import cats.effect.kernel.Ref
   import cats.syntax.all._
   import org.http4s._
   import org.http4s.dsl.io._
   import org.http4s.circe._
   import io.circe.generic.auto._
   import org.typelevel.log4cats.slf4j.Slf4jLogger // not needed
   ```
   Actually, `dsl.io._` is correct for http4s 0.23.

   Now, `freshApp`:
   ```scala
   def freshApp: IO[HttpApp[IO]] = for {
     store <- Ref.of[IO, Map[Long, Task]](Map.empty)
     counter <- Ref.of[IO, Long](1L)
   } yield routes(store, counter).orNotFound
   ```

   Main:
   ```scala
   object Main extends IOApp.Simple {
     val run = freshApp.flatMap { app =>
       org.http4s.ember.server.EmberServerBuilder[IO]
         .bindHttp(3000, "0.0.0.0")
         .withHttpApp(app)
         .build
         .useForever
     }
   }
   ```

   Let's assemble `main.scala` with all directives and imports.

   Check dependencies:
   ```scala
   //> using scala 3.3.4
   //> using dep org.http4s::http4s-ember-server:0.23.30
   //> using dep org.http4s::http4s-dsl:0.23.30
   //> using dep org.http4s::http4s-circe:0.23.30
   //> using dep io.circe::circe-generic:0.14.10
   //> using test.dep org.scalameta::munit::1.0.4
   ```

   Wait, `http4s-circe` in 0.23.30 might require `circe-core` or similar, but `circe-generic` brings it in. It's fine.
   Also, `dsl.io._` might require `org.http4s.dsl.io._` or just `org.http4s.dsl._` with `cats.effect.IO` in scope. In 0.23, it's `org.http4s.dsl.io._`.

   Let's verify pattern matching syntax for http4s 0.23:
   ```scala
   import org.http4s.dsl.io._
   HttpRoutes.of[IO] {
     case GET -> Root / "health" => Ok(...)
     case GET -> Root / "tasks" => ...
     case POST -> Root / "tasks" => ...
     case GET -> Root / "tasks" / LongVar(id) => ...
     case PUT -> Root / "tasks" / LongVar(id) => ...
     case DELETE -> Root / "tasks" / LongVar(id) => ...
   }
   ```
   This is standard.

   One detail: `req.as[T]` requires an implicit `EntityDecoder[IO, T]`. http4s-circe provides it via `circeEntityDecoder[IO]` or automatically if we import `org.http4s.circe._`. Actually, in 0.23, you often need to import `org.http4s.circe.CirceEntityCodec.circeEntityDecoder` or similar, but `org.http4s.circe._` usually brings in the necessary implicits. To be safe, I'll import `org.http4s.circe._`.

   Let's draft `main.scala` completely:

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
   import org.typelevel.ci._

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

   Wait, `HttpRoutes.of` pattern matching might need `import org.http4s.syntax.all._` or similar? No, `dsl.io._` is enough.
   Also, `req.as[T]` might throw if JSON is malformed. That's acceptable for this scope.
   One thing: `LongVar` is from `org.http4s.dsl.io._`. Correct.
   `Root` is from `org.http4s._`. Correct.
   `GET`, `POST`, etc. are from `org.http4s.dsl.io._`. Correct.

   Let's check `freshApp` signature requirement: "Expose `def routes(...)` plus a helper `def freshApp: IO[HttpApp[IO]]`". I put them in `object App`. That's fine. Or I can put them at top level. I'll keep them in `object App` for cleanliness, or just at top level. The prompt says "Expose `def routes(...)`", doesn't specify object. I'll put them in `object App` or just at top level. I'll stick with `object App`.

   Now tests: `main.test.scala`
   Requirements:
   - Use munit
   - Drive `HttpApp[IO]` directly
   - Cover 5 cases.
   - No real TCP port.

   Draft tests:
   ```scala
   import cats.effect.IO
   import cats.effect.unsafe.implicits.global
   import munit.FunSuite
   import org.http4s._
   import org.http4s.Method._
   import org.http4s.Status._
   import org.http4s.circe._
   import io.circe.generic.auto._

   class TaskApiSuite extends FunSuite {
     def createApp(): IO[HttpApp[IO]] = App.freshApp

     test("GET /health returns 200") {
       val result = createApp().flatMap { app =>
         val req = Request[IO](method = GET, uri = uri"/health")
         app(req).map(_.status)
       }.unsafeRunSync()
       assertEquals(result, Ok)
     }

     test("POST /tasks returns 201 and id 1") {
       val result = createApp().flatMap { app =>
         val req = Request[IO](
           method = POST,
           uri = uri"/tasks",
           entity = """{"title":"First task"}"""
         )
         app(req).flatMap { resp =>
           resp.as[Task].map(t => (resp.status, t.id))
         }
       }.unsafeRunSync()
       assertEquals(result, (Created, 1L))
     }

     test("GET /tasks/1 after creation returns the task") {
       val result = createApp().flatMap { app =>
         val createReq = Request[IO](
           method = POST,
           uri = uri"/tasks",
           entity = """{"title":"Test"}"""
         )
         for {
           _ <- app(createReq)
           getReq = Request[IO](method = GET, uri = uri"/tasks/1")
           task <- app(getReq).flatMap(_.as[Task])
         } yield task.title
       }.unsafeRunSync()
       assertEquals(result, "Test")
     }

     test("GET /tasks/999 returns 404") {
       val result = createApp().flatMap { app =>
         val req = Request[IO](method = GET, uri = uri"/tasks/999")
         app(req).map(_.status)
       }.unsafeRunSync()
       assertEquals(result, NotFound)
     }

     test("DELETE an existing task returns 204, and a subsequent GET returns 404") {
       val result = createApp().flatMap { app =>
         val createReq = Request[IO](
           method = POST,
           uri = uri"/tasks",
           entity = """{"title":"To delete"}"""
         )
         for {
           _ <- app(createReq)
           deleteReq = Request[IO](method = DELETE, uri = uri"/tasks/1")
           deleteResp <- app(deleteReq)
           getReq = Request[IO](method = GET, uri = uri"/tasks/1")
           getResp <- app(getReq)
         } yield (deleteResp.status, getResp.status)
       }.unsafeRunSync()
       assertEquals(result, (NoContent, NotFound))
     }
   }
   ```

   Wait, `uri"/health"` syntax requires `import org.http4s.Uri._` or similar. In http4s 0.23, string interpolation for URI is available via `import org.http4s.Uri._` or `import org.http4s.syntax.all._`. Actually, `uri"/tasks"` is from `org.http4s.Uri._`. Let's add that import. Or just use `Uri.fromString("/tasks").valueOrThrow`. I'll use `Uri.fromString(...)` to be safe, or import `org.http4s.Uri._`. Actually, `org.http4s.syntax.all._` provides `uri""`. I'll add `import org.http4s.syntax.all._`.

   Let's adjust test imports:
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
   ```

   Check `App.freshApp` visibility: It's in `object App`, so tests can call `App.freshApp`. That's fine.

   One detail: `req.as[Task]` in tests requires `EntityDecoder[IO, Task]`. `import org.http4s.circe._` brings it.
   Also, `entity = """{"title":"First task"}"""` works because http4s has an implicit `EntityEncoder[IO, String]`.
   All good.

   Let's verify http4s 0.23.30 compatibility:
   - `HttpRoutes.of[IO] { case ... }` is standard.
   - `dsl.io._` is correct.
   - `ember-server` builder: `EmberServerBuilder[IO].bindHttp(3000, "0.0.0.0").withHttpApp(app).build.useForever` is correct.
   - `Ref.of` is in `cats.effect.kernel.Ref` (or `cats.effect.Ref` in newer cats-effect 3, but `kernel` is fine). I'll use `cats.effect.IO` and `cats.effect.Ref` to be safe, or just `cats.effect.kernel.Ref`. Actually, in CE3, `Ref` is in `cats.effect.kernel`. I'll stick with `cats.effect.kernel.Ref`.

   Wait, `cats.effect.unsafe.implicits.global` is deprecated in newer CE3, but still works. Alternatively, use `cats.effect.unsafe.IORuntime.global` or just `cats.effect.unsafe.implicits.global`. For simplicity in tests, it's fine. Or I can use `IOApp` style or `unsafeRunSync()` directly. I'll keep it.

   Let's double check the `routes` function signature requirement: "Expose `def routes(store: Ref[IO, ...], counter: Ref[IO, Long]): HttpRoutes[IO]`". I have exactly that.
   "plus a helper `def freshApp: IO[HttpApp[IO]]`". I have that.
   "Derive circe codecs for `Task`". I used `io.circe.generic.auto._`.

   One minor thing: `HttpRoutes.of` pattern matching might need `import org.http4s.dsl.io._` which I have.
   Also, `LongVar` is from `org.http4s.dsl.io._`.
   `Root` is from `org.http4s._`.
   `GET`, `POST`, etc. are from `org.http4s.dsl.io._`.

   Let's verify the exact syntax for `HttpRoutes.of` in 0.23:
   ```scala
   import org.http4s.dsl.io._
   HttpRoutes.of[IO] {
     case GET -> Root / "health" => Ok(...)
   }
   ```
   This is correct.

   One potential issue: `req.as[T]` might fail if content-type is missing. http4s usually infers JSON from the body if needed, or we can set `contentType = ContentType(`application/json`)`. I'll add it to be robust, or leave it. I'll set it in tests to be safe.

   Let's adjust test requests to include content type:
   ```scala
   val req = Request[IO](
     method = POST,
     uri = uri"/tasks",
     entity = """{"title":"First task"}""",
     headers = Headers(Header("Content-Type", "application/json"))
   )
   ```
   Actually, http4s's `EntityEncoder.stringEncoder` defaults to `text/plain`, but `circe` decoder might still work. To be strictly correct, I'll add `Content-Type: application/json`.

   Alternatively, use `EntityEncoder[IO, String].apply(...)` but it's fine.

   Let's refine tests slightly for robustness:
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

   Check `App` object placement: It's in `main.scala`. Tests import it via `App.freshApp`. That's fine.
   One thing: `HttpRoutes.of` pattern matching might need `import cats.syntax.all._` for `.as`? No, `.as` is from cats. I have it.
   Also, `store.getAndUpdate(_. - id)` is correct.
   `counter.getAndUpdate(_ + 1)` is correct.

   Let's verify `HttpRoutes.of` syntax again. In http4s 0.23, it's:
   ```scala
   import org.http4s.dsl.io._
   HttpRoutes.of[IO] {
     case GET -> Root / "health" => Ok(...)
   }
   ```
   This relies on `dsl.io._` which provides `GET`, `Root`, `/`, `LongVar`, etc. Actually, `Root` and `/` are in `org.http4s._` and `org.http4s.syntax.all._` or `org.http4s.dsl.io._`. I'll add `import org.http4s.syntax.all._` to be safe.

   Wait, `org.http4s.dsl.io._` in 0.23 actually brings in the route macros. Let's check:
   Usually:
   ```scala
   import org.http4s.dsl.io._
   import org.http4s._
   import org.http4s.syntax.all._
   ```
   I'll include them.

   One more check: `req.as[CreateTask]` might fail if the request body is empty or malformed. The tests provide valid JSON, so it's fine.

   Let's assemble the final `main.scala`:

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

   Check `main.test.scala`:
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

   One minor point: `cats.effect.unsafe.implicits.global` is deprecated in CE3.2+, but still works. To be strictly modern, I could use `cats.effect.unsafe.IORuntime.global` or just rely on `munit`'s support, but `unsafeRunSync()` needs an `IORuntime`. I'll keep it as is for simplicity, or use `cats.effect.unsafe.implicits.global` which is standard in many examples. Actually, `cats.effect.unsafe.implicits.global` is fine.

   Another point: `HttpRoutes.of` pattern matching might need `import org.http4s.dsl.io._` which I have.
   All requirements met.
   Output format: strictly `### FILE: main.scala` and `### FILE: main.test.scala` with code blocks.
   I will produce exactly that.
   Double check dependencies: exactly as requested.
   Data model: exactly as requested.
   Endpoints: all covered.
   Implementation constraints: `cats.effect.IO`, `HttpRoutes`, `Ref`, `routes(...)`, `freshApp`, circe codecs, `Main extends IOApp.Simple`.
   Tests: munit, direct `HttpApp` drive, 5 cases covered.
   Ready.
   One thing: `HttpRoutes.of` might require `import cats.effect.IO` which is there.
   I'll output the files now. 
   Self-Correction/Verification during thought:
   - `LongVar` is correct.
   - `Root / "tasks" / LongVar(id)` is correct.
   - `req.as[CreateTask]` works with `circe-generic`.
   - `App.freshApp` is public.
   - `Main` uses `EmberServerBuilder`.
   - Tests use `unsafeRunSync()` which requires `IORuntime`. `cats.effect.unsafe.implicits.global` provides it.
   - All good.
   Output matches requirements.
   Proceed. 
   Generating response. 
   [Output Generation] -> matches the final code. 
   Note: I'll make sure the code blocks are exactly as requested. No extra text.
   One detail: `//> using scala 3.3.4` etc. must be at the top of `main.scala`.
   All set. 
   Output matches. 
   [Done] 
   *Self-Correction/Refinement during generation prep*: 
   I should ensure `HttpRoutes.of` syntax compiles with the given imports. In http4s 0.23, `dsl.io._` provides the route combinators. `Root` and `/` are in `org.http4s._` and `org.http4s.syntax.all._`. I've imported them. It's fine.
   I'll produce the exact files. 
   Proceeds. 
   [Output Generation] (matches the final code blocks)
   *(Done.)*
