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
	"strings"
	"sync"
)

type Task struct {
	ID    uint64 `json:"id"`
	Title string `json:"title"`
	Done  bool   `json:"done"`
}

type TaskStore struct {
	mu     sync.RWMutex
	tasks  map[uint64]Task
	nextID uint64
}

func NewTaskStore() *TaskStore {
	return &TaskStore{
		tasks:  make(map[uint64]Task),
		nextID: 1,
	}
}

func (s *TaskStore) GetAll() []Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]Task, 0, len(s.tasks))
	for _, task := range s.tasks {
		result = append(result, task)
	}
	// Sort by ID ascending
	for i := 0; i < len(result)-1; i++ {
		for j := i + 1; j < len(result); j++ {
			if result[i].ID > result[j].ID {
				result[i], result[j] = result[j], result[i]
			}
		}
	}
	return result
}

func (s *TaskStore) Create(title string) Task {
	s.mu.Lock()
	defer s.mu.Unlock()

	task := Task{
		ID:    s.nextID,
		Title: title,
		Done:  false,
	}
	s.tasks[s.nextID] = task
	s.nextID++
	return task
}

func (s *TaskStore) Get(id uint64) (Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	task, ok := s.tasks[id]
	return task, ok
}

func (s *TaskStore) Update(id uint64, title string, done bool) (Task, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, ok := s.tasks[id]
	if !ok {
		return Task{}, false
	}
	task.Title = title
	task.Done = done
	s.tasks[id] = task
	return task, true
}

func (s *TaskStore) Delete(id uint64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, ok := s.tasks[id]; !ok {
		return false
	}
	delete(s.tasks, id)
	return true
}

func App() http.Handler {
	store := NewTaskStore()
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		tasks := store.GetAll()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(tasks)
	})

	mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
		var input struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			http.Error(w, "invalid JSON", http.StatusBadRequest)
			return
		}
		defer r.Body.Close()

		task := store.Create(input.Title)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("GET /tasks/", func(w http.ResponseWriter, r *http.Request) {
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid ID", http.StatusBadRequest)
			return
		}

		task, ok := store.Get(id)
		if !ok {
			http.Error(w, "task not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("PUT /tasks/", func(w http.ResponseWriter, r *http.Request) {
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid ID", http.StatusBadRequest)
			return
		}

		var input struct {
			Title string `json:"title"`
			Done  bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			http.Error(w, "invalid JSON", http.StatusBadRequest)
			return
		}
		defer r.Body.Close()

		task, ok := store.Update(id, input.Title, input.Done)
		if !ok {
			http.Error(w, "task not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("DELETE /tasks/", func(w http.ResponseWriter, r *http.Request) {
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid ID", http.StatusBadRequest)
			return
		}

		if !store.Delete(id) {
			http.Error(w, "task not found", http.StatusNotFound)
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
	"testing"
)

func TestHealth(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rec.Code)
	}

	var resp map[string]string
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if resp["status"] != "ok" {
		t.Errorf("expected status 'ok', got '%s'", resp["status"])
	}
}

func TestCreateTask(t *testing.T) {
	store := NewTaskStore()
	// We'll test through App() which creates its own store, so we can't verify
	// internal state. Instead, we rely on subsequent requests.
	req := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"test"}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Errorf("expected status %d, got %d", http.StatusCreated, rec.Code)
	}

	var task Task
	if err := json.NewDecoder(rec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if task.Title != "test" {
		t.Errorf("expected title 'test', got '%s'", task.Title)
	}
	if task.Done {
		t.Error("expected done to be false")
	}
	if task.ID != 1 {
		t.Errorf("expected ID 1, got %d", task.ID)
	}
}

func TestGetTask(t *testing.T) {
	// First create a task
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"get me"}`))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	App().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: got status %d", createRec.Code)
	}

	// Then get it
	getReq := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	getRec := httptest.NewRecorder()
	App().ServeHTTP(getRec, getReq)

	if getRec.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, getRec.Code)
	}

	var task Task
	if err := json.NewDecoder(getRec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if task.Title != "get me" {
		t.Errorf("expected title 'get me', got '%s'", task.Title)
	}
}

func TestGetTaskNotFound(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Errorf("expected status %d, got %d", http.StatusNotFound, rec.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	// Create task first
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"delete me"}`))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	App().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: got status %d", createRec.Code)
	}

	// Delete it
	deleteReq := httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
	deleteRec := httptest.NewRecorder()
	App().ServeHTTP(deleteRec, deleteReq)

	if deleteRec.Code != http.StatusNoContent {
		t.Errorf("expected status %d, got %d", http.StatusNoContent, deleteRec.Code)
	}

	// Verify it's gone
	getReq := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	getRec := httptest.NewRecorder()
	App().ServeHTTP(getRec, getReq)

	if getRec.Code != http.StatusNotFound {
		t.Errorf("expected status %d after deletion, got %d", http.StatusNotFound, getRec.Code)
	}
}
```