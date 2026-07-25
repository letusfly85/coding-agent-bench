Implement a small REST API in Go using only the standard library.

## Requirements

Module name: `task_api`. Go 1.22 or newer (use the enhanced `http.ServeMux` routing patterns).

### Dependencies
Standard library only. No third-party modules.

### Data model
```go
type Task struct {
    ID    uint64 `json:"id"`
    Title string `json:"title"`
    Done  bool   `json:"done"`
}
```
`ID` is assigned by the server, starting at 1 and incrementing.

### Endpoints
| Method | Path | Behaviour |
|---|---|---|
| GET | `/health` | 200, body `{"status":"ok"}` |
| GET | `/tasks` | 200, JSON array of all tasks, ordered by ascending id |
| POST | `/tasks` | Body `{"title":"..."}`. Creates a task with `done=false`. Returns 201 with the created task. |
| GET | `/tasks/{id}` | 200 with the task, or 404 if absent |
| PUT | `/tasks/{id}` | Body `{"title":"...","done":true}`. Returns 200 with the updated task, or 404 if absent. |
| DELETE | `/tasks/{id}` | 204 if deleted, 404 if absent |

### Implementation constraints
- In-memory state only, guarded by a `sync.RWMutex`.
- Expose `func App() http.Handler` that builds the mux with fresh state, so tests can call it directly.
- `main` listens on `:3000` and serves `App()`.
- Must pass `go vet ./...`.

### Tests
Include `main_test.go` driving the handler via `httptest.NewRecorder` and `App()` — do **not** bind a real TCP port in tests. Cover at minimum:
1. `GET /health` returns 200
2. `POST /tasks` returns 201 and id 1
3. `GET /tasks/1` after creation returns the task
4. `GET /tasks/999` returns 404
5. `DELETE` an existing task returns 204, and a subsequent `GET` returns 404

## Output format

Output **only** the files, each introduced by a `### FILE: <relative path>` line followed by a single fenced code block. No commentary before, between, or after the files.

### FILE: go.mod
```
...
```

### FILE: main.go
```go
...
```

### FILE: main_test.go
```go
...
```
