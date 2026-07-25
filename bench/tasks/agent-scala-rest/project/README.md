# task_api

An in-memory task REST API on http4s.

`main.test.scala` is the specification. Do not modify it — implement `main.scala`
so that `scala-cli test .` passes.

## Required endpoints

| Method | Path | Behaviour |
|---|---|---|
| GET | /health | 200, body `{"status":"ok"}` |
| GET | /tasks | 200, JSON array of all tasks, ascending id |
| POST | /tasks | body `{"title":"..."}` -> 201 with the created task, done=false, id from 1 |
| GET | /tasks/{id} | 200 with the task, or 404 |
| PUT | /tasks/{id} | body `{"title":"...","done":true}` -> 200 with updated task, or 404 |
| DELETE | /tasks/{id} | 204, or 404 if absent |

## Required API

`main.scala` must define `case class Task(id: Long, title: String, done: Boolean)`
and an object `Api` exposing `def freshApp: IO[HttpApp[IO]]` over fresh state.

The dependency directives must live in `main.scala` and match those already
declared at the top of `main.test.scala`.
