The user wants a Go REST API using only the standard library, Go 1.22+, with specific endpoints and constraints.
I need to output exactly three files:
- go.mod
- main.go
- main_test.go

Constraints:
- Module name: `task_api`
- Go 1.22+
- Use enhanced `http.ServeMux` routing patterns (e.g., `/tasks/{id}`)
- Data model: Task struct with ID, Title, Done
- In-memory state, guarded by sync.RWMutex
- Expose `func App() http.Handler`
- main listens on :3000
- Tests using httptest.NewRecorder, covering specific cases
- No third-party dependencies
- Must pass `go vet ./...`

Let's design `main.go`:

```go
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"
	"sync/atomic"
	"time"
)

type Task struct {
	ID    uint64 `json:"id"`
	Title string `json:"title"`
	Done  bool   `json:"done"`
}

type store struct {
	mu    sync.RWMutex
	tasks map[uint64]Task
	nextID atomic.Uint64
}

func newStore() *store {
	s := &store{
		tasks: make(map[uint64]Task),
	}
	s.nextID.Store(1)
	return s
}

func App() http.Handler {
	s := newStore()
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		s.mu.RLock()
		defer s.mu.RUnlock()

		out := make([]Task, 0, len(s.tasks))
		for _, t := range s.tasks {
			out = append(out, t)
		}
		// Ensure ascending order by id
		// Since we control IDs and don't reorder, they are naturally ascending if we iterate in order,
		// but map iteration is random. So sort.
		// Use simple sort since it's small.
		for i := 0; i < len(out); i++ {
			for j := i + 1; j < len(out); j++ {
				if out[i].ID > out[j].ID {
					out[i], out[j] = out[j], out[i]
				}
			}
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(out)
	})

	mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
		var input struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if input.Title == "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		id := s.nextID.Add(1) - 1
		t := Task{
			ID:    id,
			Title: input.Title,
			Done:  false,
		}

		s.mu.Lock()
		s.tasks[id] = t
		s.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("GET /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.RLock()
		t, ok := s.tasks[id]
		s.mu.RUnlock()

		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("PUT /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		var input struct {
			Title *string `json:"title"`
			Done  *bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.Lock()
		t, ok := s.tasks[id]
		if !ok {
			s.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}

		if input.Title != nil {
			t.Title = *input.Title
		}
		if input.Done != nil {
			t.Done = *input.Done
		}

		s.tasks[id] = t
		s.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("DELETE /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.Lock()
		_, ok := s.tasks[id]
		if !ok {
			s.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}

		delete(s.tasks, id)
		s.mu.Unlock()

		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

func parseUint64PathVar(r *http.Request, name string) uint64 {
	v := r.PathValue(name)
	var id uint64
	_, err := fmt.Sscanf(v, "%d", &id)
	if err != nil {
		return 0
	}
	return id
}

func main() {
	h := App()
	log.Println("Listening on :3000")
	if err := http.ListenAndServe(":3000", h); err != nil {
		log.Fatal(err)
	}
}
```

Wait, Go 1.22 enhanced ServeMux uses `r.PathValue(name)` for path variables. That's correct.
I should ensure `time` import is removed if unused. I imported `time` but didn't use it. I'll remove it.
I used `sync/atomic` for `nextID`. That's good.
I should ensure that `App()` returns a handler that's safe for concurrent use.
The store is captured in the closure, so it's fine.

Let's refine `main.go` to be clean and correct.

```go
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sort"
	"sync"
	"sync/atomic"
)

type Task struct {
	ID    uint64 `json:"id"`
	Title string `json:"title"`
	Done  bool   `json:"done"`
}

type store struct {
	mu     sync.RWMutex
	tasks  map[uint64]Task
	nextID atomic.Uint64
}

func newStore() *store {
	s := &store{
		tasks: make(map[uint64]Task),
	}
	s.nextID.Store(1)
	return s
}

func App() http.Handler {
	s := newStore()
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		s.mu.RLock()
		out := make([]Task, 0, len(s.tasks))
		for _, t := range s.tasks {
			out = append(out, t)
		}
		s.mu.RUnlock()

		sort.Slice(out, func(i, j int) bool {
			return out[i].ID < out[j].ID
		})

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(out)
	})

	mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
		var input struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if input.Title == "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		id := s.nextID.Add(1) - 1
		t := Task{
			ID:    id,
			Title: input.Title,
			Done:  false,
		}

		s.mu.Lock()
		s.tasks[id] = t
		s.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("GET /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.RLock()
		t, ok := s.tasks[id]
		s.mu.RUnlock()

		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("PUT /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		var input struct {
			Title *string `json:"title"`
			Done  *bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.Lock()
		t, ok := s.tasks[id]
		if !ok {
			s.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}

		if input.Title != nil {
			t.Title = *input.Title
		}
		if input.Done != nil {
			t.Done = *input.Done
		}

		s.tasks[id] = t
		s.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("DELETE /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.Lock()
		_, ok := s.tasks[id]
		if !ok {
			s.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}

		delete(s.tasks, id)
		s.mu.Unlock()

		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

func parseUint64PathVar(r *http.Request, name string) uint64 {
	v := r.PathValue(name)
	var id uint64
	_, err := fmt.Sscanf(v, "%d", &id)
	if err != nil {
		return 0
	}
	return id
}

func main() {
	h := App()
	log.Println("Listening on :3000")
	if err := http.ListenAndServe(":3000", h); err != nil {
		log.Fatal(err)
	}
}
```

Now `main_test.go`:
Requirements:
- Use `httptest.NewRecorder` and `App()`
- No real TCP port
- Cover:
  1. GET /health -> 200
  2. POST /tasks -> 201, id 1
  3. GET /tasks/1 -> returns task
  4. GET /tasks/999 -> 404
  5. DELETE existing -> 204, subsequent GET -> 404

I'll write a test file that does this.

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
	mux := App()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}

	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if resp["status"] != "ok" {
		t.Fatalf("expected status ok, got %v", resp)
	}
}

func TestCreateAndGetTask(t *testing.T) {
	mux := App()

	// POST /tasks
	body := bytes.NewReader([]byte(`{"title":"Test Task"}`))
	req := httptest.NewRequest(http.MethodPost, "/tasks", body)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()

	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("POST /tasks: expected 201, got %d", rec.Code)
	}

	var created Task
	if err := json.Unmarshal(rec.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	if created.ID != 1 {
		t.Fatalf("expected id 1, got %d", created.ID)
	}

	// GET /tasks/1
	req = httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("GET /tasks/1: expected 200, got %d", rec.Code)
	}

	var fetched Task
	if err := json.Unmarshal(rec.Body.Bytes(), &fetched); err != nil {
		t.Fatal(err)
	}
	if fetched.Title != "Test Task" {
		t.Fatalf("expected title 'Test Task', got %s", fetched.Title)
	}
}

func TestGetNonExistentTask(t *testing.T) {
	mux := App()
	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	rec := httptest.NewRecorder()

	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", rec.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	mux := App()

	// Create task
	body := bytes.NewReader([]byte(`{"title":"To Delete"}`))
	req := httptest.NewRequest(http.MethodPost, "/tasks", body)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	var created Task
	json.Unmarshal(rec.Body.Bytes(), &created)

	// DELETE /tasks/{id}
	req = httptest.NewRequest(http.MethodDelete, "/tasks/"+fmt.Sprint(created.ID), nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("DELETE: expected 204, got %d", rec.Code)
	}

	// GET again -> 404
	req = httptest.NewRequest(http.MethodGet, "/tasks/"+fmt.Sprint(created.ID), nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: expected 404, got %d", rec.Code)
	}
}
```

Wait, I used `fmt.Sprint` in test but didn't import `fmt`. I'll add it.
Also, the test file should be self-contained.

Let's check `go.mod`:
```
module task_api

go 1.22
```

Check requirements:
- Module name: `task_api` -> yes
- Go 1.22 or newer -> yes
- Use enhanced `http.ServeMux` routing patterns -> yes (`"GET /tasks/{id}"`, etc.)
- Standard library only -> yes
- Data model matches -> yes
- ID assigned by server, starting at 1 incrementing -> yes (`atomic.Uint64`)
- Endpoints match behaviors -> yes
- In-memory state, guarded by `sync.RWMutex` -> yes
- Expose `func App() http.Handler` -> yes
- `main` listens on `:3000` -> yes
- Must pass `go vet ./...` -> I'll ensure no issues. `fmt.Sscanf` is fine. `json.NewEncoder` is fine.
- Tests cover specified cases -> yes.

One detail: `App()` creates a new store each time it's called. That's fine and matches "builds the mux with fresh state, so tests can call it directly."

Let's double check the `parseUint64PathVar` function. `r.PathValue(name)` is available in Go 1.22.
`fmt.Sscanf(v, "%d", &id)` works for uint64? Yes, `%d` works for signed, but for positive IDs it's fine. To be strictly correct, I could use `%d` or `%u`. `%d` is fine. Or I can use `strconv.ParseUint`. Let's use `strconv.ParseUint` to be cleaner and avoid `fmt` dependency if possible, but `fmt` is standard. I'll stick with `strconv` for robustness.

Actually, `strconv.ParseUint(v, 10, 64)` is better.

Let's adjust `parseUint64PathVar`:
```go
import "strconv"

func parseUint64PathVar(r *http.Request, name string) uint64 {
	v := r.PathValue(name)
	id, err := strconv.ParseUint(v, 10, 64)
	if err != nil {
		return 0
	}
	return id
}
```
This avoids `fmt` import in main.go. I'll remove `fmt` from main.go imports.

Check `main.go` imports:
```go
import (
	"encoding/json"
	"log"
	"net/http"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
)
```
Looks good.

Check `main_test.go` imports:
```go
import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)
```
Looks good.

One thing: The requirement says "GET /tasks/{id} | 200 with the task, or 404 if absent".
My implementation does that.
"PUT /tasks/{id} | Body {"title":"...","done":true}. Returns 200 with the updated task, or 404 if absent."
My implementation handles partial updates using pointers, which is standard RESTful. If they want exact match, it's fine. The requirement shows an example body, not strict validation. I'll keep partial update support as it's idiomatic.

Check `GET /tasks` ordering: "ordered by ascending id". I used `sort.Slice`. Good.

Check `POST /tasks`: "Body {"title":"..."}. Creates a task with done=false. Returns 201 with the created task."
My code checks `input.Title == ""` and returns 400. That's fine.

Check `DELETE /tasks/{id}`: "204 if deleted, 404 if absent".
My code does exactly that.

Check `GET /health`: "200, body {"status":"ok"}".
My code does that.

All constraints met.

Let's verify `go vet` compatibility:
- `sync/atomic.Uint64` is available in Go 1.19+.
- `r.PathValue` is Go 1.22+.
- `http.NewServeMux().HandleFunc("METHOD /path/{var}", ...)` is Go 1.22+.
Everything is standard.

One minor point: In tests, calling `App()` multiple times creates multiple stores, which is intended ("fresh state").

I will produce the output exactly as requested.

Structure:
### FILE: go.mod
### FILE: main.go
### FILE: main_test.go

Code blocks only. No extra text.

Let's assemble carefully.
One detail: `go.mod` should specify `go 1.22`.
```
module task_api

go 1.22
```

`main.go`:
```go
package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
)

type Task struct {
	ID    uint64 `json:"id"`
	Title string `json:"title"`
	Done  bool   `json:"done"`
}

type store struct {
	mu     sync.RWMutex
	tasks  map[uint64]Task
	nextID atomic.Uint64
}

func newStore() *store {
	s := &store{
		tasks: make(map[uint64]Task),
	}
	s.nextID.Store(1)
	return s
}

func App() http.Handler {
	s := newStore()
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		s.mu.RLock()
		out := make([]Task, 0, len(s.tasks))
		for _, t := range s.tasks {
			out = append(out, t)
		}
		s.mu.RUnlock()

		sort.Slice(out, func(i, j int) bool {
			return out[i].ID < out[j].ID
		})

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(out)
	})

	mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
		var input struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if input.Title == "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		id := s.nextID.Add(1) - 1
		t := Task{
			ID:    id,
			Title: input.Title,
			Done:  false,
		}

		s.mu.Lock()
		s.tasks[id] = t
		s.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("GET /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.RLock()
		t, ok := s.tasks[id]
		s.mu.RUnlock()

		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("PUT /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		var input struct {
			Title *string `json:"title"`
			Done  *bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.Lock()
		t, ok := s.tasks[id]
		if !ok {
			s.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}

		if input.Title != nil {
			t.Title = *input.Title
		}
		if input.Done != nil {
			t.Done = *input.Done
		}

		s.tasks[id] = t
		s.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("DELETE /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.Lock()
		_, ok := s.tasks[id]
		if !ok {
			s.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}

		delete(s.tasks, id)
		s.mu.Unlock()

		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

func parseUint64PathVar(r *http.Request, name string) uint64 {
	v := r.PathValue(name)
	id, err := strconv.ParseUint(v, 10, 64)
	if err != nil {
		return 0
	}
	return id
}

func main() {
	h := App()
	log.Println("Listening on :3000")
	if err := http.ListenAndServe(":3000", h); err != nil {
		log.Fatal(err)
	}
}
```

`main_test.go`:
```go
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealth(t *testing.T) {
	mux := App()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}

	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if resp["status"] != "ok" {
		t.Fatalf("expected status ok, got %v", resp)
	}
}

func TestCreateAndGetTask(t *testing.T) {
	mux := App()

	body := bytes.NewReader([]byte(`{"title":"Test Task"}`))
	req := httptest.NewRequest(http.MethodPost, "/tasks", body)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()

	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("POST /tasks: expected 201, got %d", rec.Code)
	}

	var created Task
	if err := json.Unmarshal(rec.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	if created.ID != 1 {
		t.Fatalf("expected id 1, got %d", created.ID)
	}

	req = httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("GET /tasks/1: expected 200, got %d", rec.Code)
	}

	var fetched Task
	if err := json.Unmarshal(rec.Body.Bytes(), &fetched); err != nil {
		t.Fatal(err)
	}
	if fetched.Title != "Test Task" {
		t.Fatalf("expected title 'Test Task', got %s", fetched.Title)
	}
}

func TestGetNonExistentTask(t *testing.T) {
	mux := App()
	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	rec := httptest.NewRecorder()

	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", rec.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	mux := App()

	body := bytes.NewReader([]byte(`{"title":"To Delete"}`))
	req := httptest.NewRequest(http.MethodPost, "/tasks", body)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	var created Task
	json.Unmarshal(rec.Body.Bytes(), &created)

	req = httptest.NewRequest(http.MethodDelete, "/tasks/"+fmt.Sprint(created.ID), nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("DELETE: expected 204, got %d", rec.Code)
	}

	req = httptest.NewRequest(http.MethodGet, "/tasks/"+fmt.Sprint(created.ID), nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: expected 404, got %d", rec.Code)
	}
}
```

Check `go vet` mentally:
- No unused imports.
- No unused variables.
- `sync/atomic.Uint64` usage is correct.
- `r.PathValue` is correct for Go 1.22.
- `json.Unmarshal` and `json.NewEncoder` usage correct.
- `httptest` usage correct.
Everything looks solid.

One minor thing: `App()` is called multiple times in tests, each time creating a new store. That's fine and matches "builds the mux with fresh state".
The requirements say "Expose func App() http.Handler that builds the mux with fresh state, so tests can call it directly."
So this is exactly what's needed.

Final check of the prompt: "Output only the files, each introduced by a ### FILE: <relative path> line followed by a single fenced code block. No commentary before, between, or after the files."

I will format exactly as requested.
Proceed. 
All good.
Output matches requirements.
Done. 
Self-Correction/Verification during thought:
- `go.mod` should not have extra spaces.
- `main.go` uses `http.NewServeMux()` which is correct.
- `main_test.go` covers all 5 cases.
- `sync.RWMutex` is used.
- `ID` starts at 1 and increments.
- `GET /tasks` returns sorted by id.
- All standard library.
- Go 1.22 patterns used.
Ready. 
Output generation. 
[Done] 
Proceeds. 
```
### FILE: go.mod
...
### FILE: main.go
...
### FILE: main_test.go
...
```
Matches exactly.
No extra text.
Code blocks fenced.
Done. 
Output matches response. 
Proceeds. 
[Final Check]
- `App()` returns `http.Handler`
- `main` listens on `:3000`
- `go vet` passes (standard patterns, no shadowing, correct types)
- Tests use `httptest`
- All constraints met.
Ready. 
Output generation. 
(Self-Correction/Note during generation prep)
I'll make sure `go.mod` specifies `go 1.22` exactly.
I'll ensure imports are grouped properly (though Go doesn't strictly require it, it's good practice).
Everything is fine.
Proceeds. 
[Output Generation] -> *Proceeds*
