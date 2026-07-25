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
	"net/http"
	"strconv"
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
		tasks: make(map[uint64]*Task),
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
	cp := *t
	return &cp, true
}

func (s *taskServer) listTasks() []Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	out := make([]Task, 0, len(s.tasks))
	for _, t := range s.tasks {
		cp := *t
		out = append(out, cp)
	}

	// Sort by ID ascending
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[i].ID > out[j].ID {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	return out
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

func parseUint64(s string) (uint64, error) {
	return strconv.ParseUint(s, 10, 64)
}

func App() http.Handler {
	s := NewTaskServer()
	mux := http.NewServeMux()

	// GET /health
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	// GET /tasks
	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		tasks := s.listTasks()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(tasks)
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
		_ = json.NewEncoder(w).Encode(t)
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
		_ = json.NewEncoder(w).Encode(t)
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
		_ = json.NewEncoder(w).Encode(t)
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

func main() {
	http.ListenAndServe(":3000", App())
}
```

### FILE: main_test.go
```go
package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
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

	// POST /tasks -> 201, id 1
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

	// GET /tasks/1 -> 200, same task
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
	if err := json.Unmarshal(rec.Body.Bytes(), &task); err != nil {
		t.Fatal(err)
	}

	// DELETE /tasks/{id} -> 204
	req = httptest.NewRequest(http.MethodDelete, "/tasks/"+strconv.FormatUint(task.ID, 10), nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", rec.Code)
	}

	// GET /tasks/{id} -> 404
	req = httptest.NewRequest(http.MethodGet, "/tasks/"+strconv.FormatUint(task.ID, 10), nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 after delete, got %d", rec.Code)
	}
}
```