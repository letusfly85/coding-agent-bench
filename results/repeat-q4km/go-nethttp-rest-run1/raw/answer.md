### FILE: go.mod
```
module task_api

go 1.22
```

### FILE: main.go
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

### FILE: main_test.go
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