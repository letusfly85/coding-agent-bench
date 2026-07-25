The user wants a small REST API in Go using only the standard library, with Go 1.22+ enhanced `http.ServeMux` routing.

Requirements:
- Module name: `task_api`
- Go 1.22+
- No third-party dependencies
- Data model: Task with ID (uint64), Title (string), Done (bool)
- ID assigned by server, starting at 1, incrementing
- Endpoints:
  - GET /health -> 200 {"status":"ok"}
  - GET /tasks -> 200 JSON array of all tasks, ordered by ascending id
  - POST /tasks -> 201 created task, done=false
  - GET /tasks/{id} -> 200 task or 404
  - PUT /tasks/{id} -> 200 updated task or 404
  - DELETE /tasks/{id} -> 204 if deleted, 404 if absent
- In-memory state, guarded by sync.RWMutex
- Expose App() http.Handler that builds mux with fresh state
- main listens on :3000 and serves App()
- Must pass go vet
- Tests: main_test.go using httptest.NewRecorder and App(), not binding real TCP port.
  - Cover:
    1. GET /health -> 200
    2. POST /tasks -> 201 and id 1
    3. GET /tasks/1 after creation -> task
    4. GET /tasks/999 -> 404
    5. DELETE existing -> 204, subsequent GET -> 404

Implementation details:
- Use enhanced ServeMux with patterns like:
  - GET /health
  - GET /tasks
  - POST /tasks
  - GET /tasks/{id}
  - PUT /tasks/{id}
  - DELETE /tasks/{id}
- Use a struct for the app with:
  - tasks map[uint64]*Task
  - nextID uint64
  - mu sync.RWMutex
- App() returns http.Handler (the mux) with fresh state.
- JSON handling with encoding/json.
- Ensure correct status codes and JSON responses.

Let's design the code.

go.mod:
```
module task_api

go 1.22
```

main.go:
- Define Task struct.
- Define server struct with tasks, nextID, mutex.
- Implement methods for CRUD.
- Build App() that creates a new server and a new ServeMux, registers routes, returns it.
- main() starts HTTP server on :3000.

Enhanced ServeMux patterns (Go 1.22+):
- GET /health
- GET /tasks
- POST /tasks
- GET /tasks/{id}
- PUT /tasks/{id}
- DELETE /tasks/{id}

Note: For /tasks/{id}, we need to parse id from mux.Vars.

Code structure:

```go
package main

import (
	"encoding/json"
	"net/http"
	"sync"
	"sync/atomic"
)

type Task struct {
	ID    uint64 `json:"id"`
	Title string `json:"title"`
	Done  bool   `json:"done"`
}

type taskServer struct {
	tasks  map[uint64]*Task
	nextID atomic.Uint64
	mu     sync.RWMutex
}

func NewTaskServer() *taskServer {
	return &taskServer{
		tasks:  make(map[uint64]*Task),
	}
}

func (s *taskServer) createTask(title string) (*Task, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	id := s.nextID.Add(1)
	t := &Task{
		ID:    id,
		Title: title,
		Done:  false,
	}
	s.tasks[id] = t
	return t, nil
}

func (s *taskServer) getTask(id uint64) (*Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	t, ok := s.tasks[id]
	if !ok {
		return nil, false
	}
	// return copy to avoid race conditions if we mutate
	cp := *t
	return &cp, true
}

func (s *taskServer) listTasks() ([]Task, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	out := make([]Task, 0, len(s.tasks))
	for _, t := range s.tasks {
		cp := *t
		out = append(out, cp)
	}

	// sort by ID ascending
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[i].ID > out[j].ID {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	return out, nil
}

func (s *taskServer) updateTask(id uint64, title string, done bool) (*Task, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	t, ok := s.tasks[id]
	if !ok {
		return nil, false
	}
	t.Title = title
	t.Done = done
	cp := *t
	return &cp, true
}

func (s *taskServer) deleteTask(id uint64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	_, ok := s.tasks[id]
	if !ok {
		return false
	}
	delete(s.tasks, id)
	return true
}

func App() http.Handler {
	s := NewTaskServer()
	mux := http.NewServeMux()

	// GET /health
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	// GET /tasks
	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		tasks, _ := s.listTasks()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(tasks)
	})

	// POST /tasks
	mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		t, _ := s.createTask(body.Title)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(t)
	})

	// GET /tasks/{id}
	mux.HandleFunc("GET /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := parseUint64(mux.Vars(r)["id"])
		if err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		t, ok := s.getTask(id)
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	// PUT /tasks/{id}
	mux.HandleFunc("PUT /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := parseUint64(mux.Vars(r)["id"])
		if err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		var body struct {
			Title string `json:"title"`
			Done  bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		t, ok := s.updateTask(id, body.Title, body.Done)
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	// DELETE /tasks/{id}
	mux.HandleFunc("DELETE /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := parseUint64(mux.Vars(r)["id"])
		if err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		if !s.deleteTask(id) {
			http.NotFound(w, r)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

func parseUint64(s string) (uint64, error) {
	// simple parse without strconv import? We can use strconv.
	// Let's import strconv.
	return 0, nil // placeholder
}

func main() {
	http.ListenAndServe(":3000", App())
}
```

I need to import strconv for parseUint64.

Also, ensure I handle errors properly and no race conditions.

Let's refine parseUint64:

```go
import (
	"encoding/json"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
)

func parseUint64(s string) (uint64, error) {
	return strconv.ParseUint(s, 10, 64)
}
```

Check for correctness:
- GET /health: returns 200 with {"status":"ok"}
- GET /tasks: returns 200 with JSON array sorted by ID ascending
- POST /tasks: returns 201 with created task
- GET /tasks/{id}: returns 200 with task or 404
- PUT /tasks/{id}: returns 200 with updated task or 404
- DELETE /tasks/{id}: returns 204 if deleted, 404 if absent

All match requirements.

Now tests: main_test.go.

Tests should:
- Use httptest.NewRecorder
- Use App() to get handler
- Not bind real TCP port
- Cover:
  1. GET /health -> 200
  2. POST /tasks -> 201 and id 1
  3. GET /tasks/1 after creation -> task
  4. GET /tasks/999 -> 404
  5. DELETE existing -> 204, subsequent GET -> 404

We can combine some into a single test or separate subtests.

Example:

```go
package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealth(t *testing.T) {
	handler := App()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if resp["status"] != "ok" {
		t.Fatalf("expected status ok, got %v", resp["status"])
	}
}

func TestCreateAndGetTask(t *testing.T) {
	handler := App()

	// POST /tasks
	body := bytes.NewReader([]byte(`{"title":"test"}`))
	req := httptest.NewRequest(http.MethodPost, "/tasks", body)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d", rec.Code)
	}
	var task Task
	if err := json.Unmarshal(rec.Body.Bytes(), &task); err != nil {
		t.Fatal(err)
	}
	if task.ID != 1 {
		t.Fatalf("expected id 1, got %d", task.ID)
	}

	// GET /tasks/1
	req = httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	var got Task
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.ID != 1 || got.Title != "test" {
		t.Fatalf("unexpected task: %+v", got)
	}
}

func TestNotFoundTask(t *testing.T) {
	handler := App()
	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", rec.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	handler := App()

	// create task
	body := bytes.NewReader([]byte(`{"title":"delete me"}`))
	req := httptest.NewRequest(http.MethodPost, "/tasks", body)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	var task Task
	json.Unmarshal(rec.Body.Bytes(), &task)

	// DELETE /tasks/{id}
	req = httptest.NewRequest(http.MethodDelete, "/tasks/"+strconv.FormatUint(task.ID, 10), nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", rec.Code)
	}

	// GET /tasks/{id} should be 404
	req = httptest.NewRequest(http.MethodGet, "/tasks/"+strconv.FormatUint(task.ID, 10), nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 after delete, got %d", rec.Code)
	}
}
```

Need to import strconv in test file.

Check constraints:
- In-memory state only, guarded by sync.RWMutex: Yes.
- App() builds mux with fresh state: Yes.
- main listens on :3000 and serves App(): Yes.
- Must pass go vet: I'll ensure no obvious issues.

One detail: In Go 1.22+, ServeMux patterns with method prefixes are allowed:
- "GET /health"
- "GET /tasks"
- "POST /tasks"
- "GET /tasks/{id}"
- "PUT /tasks/{id}"
- "DELETE /tasks/{id}"
This is correct.

Make sure to handle JSON encoding errors? Not strictly required, but good practice. For simplicity, I'll ignore them as typical in small examples, but I can add checks if needed. The requirements don't mandate exhaustive error handling.

One thing: The GET /tasks response must be JSON array of all tasks, ordered by ascending id. My implementation sorts in-memory. That's fine.

Let's assemble final files.

go.mod:
```
module task_api

go 1.22
```

main.go:
Full code with imports, structs, methods, App(), main().

main_test.go:
Full tests as above.

Check for any issues:
- Using atomic.Uint64 for nextID is safe and efficient.
- RWMutex usage is correct.
- JSON responses set Content-Type.
- Status codes match requirements.
- Tests use App() and httptest.NewRecorder.

One minor thing: In GET /tasks, I should ensure the JSON array is returned even if empty (null vs []). My code initializes out as make([]Task, 0), so json.Marshal will produce [].

Also, ensure go.mod uses go 1.22 or newer. I'll put go 1.22.

All good.

Now produce output exactly as requested: only files, each introduced by `### FILE: <relative path>` and a single fenced code block. No commentary.
I will double-check the exact formatting.

Structure:
### FILE: go.mod
```
module task_api

go 1.22
```

### FILE: main.go
```go
...
```

### FILE: main_test.go
```go
...
```

Ready.
I will generate the response now. 
Self-Correction/Verification during thought:
- Check if `http.NotFound(w, r)` is allowed: yes, standard library.
- Check if `http.Error` is allowed: yes.
- Check if `sync/atomic` is needed: yes, for `nextID`.
- Ensure `App()` returns `http.Handler`: yes, `*http.ServeMux` implements it.
- Ensure tests don't bind TCP: they use `httptest.NewRecorder`, correct.
- Ensure `go vet` compatibility: no obvious issues. Using standard patterns.

One detail: In Go 1.22+, `mux.HandleFunc("GET /tasks/{id}", ...)` is valid. I'll keep that.

All constraints met. Output matches requested format.
Proceed. 
Generating. 
[Done]
