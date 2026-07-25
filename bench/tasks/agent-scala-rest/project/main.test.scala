//> using scala 3.3.4
//> using dep org.http4s::http4s-ember-server:0.23.30
//> using dep org.http4s::http4s-dsl:0.23.30
//> using dep org.http4s::http4s-circe:0.23.30
//> using dep io.circe::circe-generic:0.14.10
//> using dep io.circe::circe-parser:0.14.10
//> using test.dep org.scalameta::munit::1.0.4

// Specification for task_api. Do not modify.
//
// Responses are inspected as untyped JSON so the implementation is free to name
// its own request types; the only things main.scala must expose are `Task` and
// `Api.freshApp`.

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.implicits._
import io.circe.Json
import io.circe.parser.parse

class ApiSpec extends munit.FunSuite:

  private def call(app: HttpApp[IO], method: Method, path: String, body: Option[String]): (Int, Json) =
    val base = Request[IO](method = method, uri = Uri.unsafeFromString(path))
    val req = body match
      case Some(b) => base.withEntity(b).putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
      case None    => base
    val res = app.run(req).unsafeRunSync()
    val text = res.bodyText.compile.string.unsafeRunSync()
    val json = if text.trim.isEmpty then Json.Null else parse(text).getOrElse(Json.Null)
    (res.status.code, json)

  private def app(): HttpApp[IO] = Api.freshApp.unsafeRunSync()

  private def str(j: Json, key: String): String =
    j.hcursor.get[String](key).getOrElse(sys.error(s"missing string $key in $j"))
  private def num(j: Json, key: String): Long =
    j.hcursor.get[Long](key).getOrElse(sys.error(s"missing number $key in $j"))
  private def bool(j: Json, key: String): Boolean =
    j.hcursor.get[Boolean](key).getOrElse(sys.error(s"missing boolean $key in $j"))

  test("health returns ok") {
    val (code, body) = call(app(), Method.GET, "/health", None)
    assertEquals(code, 200)
    assertEquals(str(body, "status"), "ok")
  }

  test("create returns 201 and id 1") {
    val (code, body) = call(app(), Method.POST, "/tasks", Some("""{"title":"first"}"""))
    assertEquals(code, 201)
    assertEquals(num(body, "id"), 1L)
    assertEquals(str(body, "title"), "first")
    assertEquals(bool(body, "done"), false)
  }

  test("get after create") {
    val a = app()
    call(a, Method.POST, "/tasks", Some("""{"title":"get me"}"""))
    val (code, body) = call(a, Method.GET, "/tasks/1", None)
    assertEquals(code, 200)
    assertEquals(str(body, "title"), "get me")
  }

  test("get missing returns 404") {
    val (code, _) = call(app(), Method.GET, "/tasks/999", None)
    assertEquals(code, 404)
  }

  test("delete then get returns 404") {
    val a = app()
    call(a, Method.POST, "/tasks", Some("""{"title":"delete me"}"""))
    assertEquals(call(a, Method.DELETE, "/tasks/1", None)._1, 204)
    assertEquals(call(a, Method.GET, "/tasks/1", None)._1, 404)
  }

  test("list is sorted by id") {
    val a = app()
    List("a", "b", "c").foreach(t => call(a, Method.POST, "/tasks", Some(s"""{"title":"$t"}""")))
    val (code, body) = call(a, Method.GET, "/tasks", None)
    assertEquals(code, 200)
    val arr = body.asArray.getOrElse(sys.error(s"expected array, got $body"))
    assertEquals(arr.length, 3)
    assertEquals(arr.map(num(_, "id")).toList, List(1L, 2L, 3L))
  }

  test("update replaces fields") {
    val a = app()
    call(a, Method.POST, "/tasks", Some("""{"title":"before"}"""))
    val (code, body) = call(a, Method.PUT, "/tasks/1", Some("""{"title":"after","done":true}"""))
    assertEquals(code, 200)
    assertEquals(str(body, "title"), "after")
    assertEquals(bool(body, "done"), true)
    assertEquals(call(a, Method.PUT, "/tasks/999", Some("""{"title":"x","done":false}"""))._1, 404)
  }
