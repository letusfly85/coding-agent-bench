# task_api

An in-memory task REST API.

`tests/api.rs` is the specification. Do not modify it — implement `src/lib.rs`
so that `cargo test` passes.

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

`src/lib.rs` must expose `pub fn app() -> axum::Router`, building a router over
**fresh** in-memory state. Tasks serialise as `{"id":1,"title":"...","done":false}`.

Note that axum 0.8 writes path parameters as `/tasks/{id}`, not `/tasks/:id`.
