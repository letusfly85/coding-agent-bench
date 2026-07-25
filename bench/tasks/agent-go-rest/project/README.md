# task_api

An in-memory task REST API.

`main_test.go` is the specification. Do not modify it — implement `main.go`
so that `go test ./...` passes.

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

Expose `func App() http.Handler` building a handler over **fresh** state, and a
`Task` struct with JSON tags `id`, `title`, `done`. Standard library only.
