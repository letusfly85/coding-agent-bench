Let's analyze the build errors:

1. "Cannot decode into a value of type UpdateTaskReq, because no EntityDecoder[cats.effect.IO, UpdateTaskReq] instance could be found."
   - We need to bring `CirceEntityCodec.circeEntityDecoder` into scope, or more conveniently, use `CirceEntityCodec._` or `CirceEntityDecoder._`.
   - The hint suggests: `import org.http4s.circe.CirceEntityCodec.circeEntityDecoder`.
   - We also likely need it for `CreateTaskReq` and `Task` as well (though Task might be inferred via generic.auto).
   - Better: import all circe-related codecs:
     - `import org.http4s.circe._`
   - And ensure `io.circe.generic.auto._` is imported.

2. "Found: (3000 : Int), Required: com.comcast.ip4s.Port"
   - Need to use `.withPort(port"3000")` instead of `.withPort(3000)`.

3. "Cannot decode into a value of type UpdateTaskReq..."
   - This is likely the same missing import issue.

4. "Cannot decode into a value of type CreateTaskReq..." (implied)
   - Same.

5. "Cannot decode into a value of type Task..."
   - Same.

So we must:
- Import `org.http4s.circe._` (or specifically `CirceEntityCodec._`, etc.)
- Use `port"3000"`.

Let's correct main.scala:

- Add:
  - `import org.http4s.circe._`
- Change `.withPort(3000)` to `.withPort(port"3000")`.

Let's also check the test file for similar issues.

In the test file:
- We use `resp.as[String]` and `decode[Task](body)`. That's fine.
- We rely on `org.http4s.circe._` for `.as[String]`? Actually `.as[String]` uses the default text decoder; we don't need circe for that.
- But we should still import `org.http4s.circe._` if needed.
- Also need `import cats.effect.IO`, `munit.CatsEffectSuite`, etc. Already there.

Let's rewrite both files cleanly.

Corrected main.scala:

- Imports:
  - cats.effect.{IO, IOApp, Ref}
  - cats.syntax.all._
  - org.http4s._
  - org.http4s.dsl.io._
  - org.http4s.implicits._
  - org.http4s.circe._
  - org.http4s.ember.server.EmberServerBuilder
  - io.circe.generic.auto._
  - io.circe.syntax._
  - com.comcast.ip4s._

- Fix .withPort(3000) -> .withPort(port"3000").

Corrected main.test.scala:
- Ensure imports are correct and use same app pattern.

Let's produce the final files.
