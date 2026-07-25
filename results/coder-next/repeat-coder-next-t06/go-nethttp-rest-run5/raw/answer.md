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

func (s *TaskStore) Add(title string) (Task, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	task := Task{
		ID:    s.nextID,
		Title: title,
		Done:  false,
	}
	s.tasks[s.nextID] = task
	s.nextID++
	return task, nil
}

func (s *TaskStore) Get(id uint64) (Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	task, ok := s.tasks[id]
	return task, ok
}

func (s *TaskStore) GetAll() []Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	tasks := make([]Task, 0, len(s.tasks))
	for i := uint64(1); i < s.nextID; i++ {
		if task, ok := s.tasks[i]; ok {
			tasks = append(tasks, task)
		}
	}
	return tasks
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
		var req struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}

		task, err := store.Add(req.Title)
		if err != nil {
			http.Error(w, "failed to create task", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("GET /tasks/", func(w http.ResponseWriter, r *http.Request) {
		// Extract ID from path
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid task id", http.StatusBadRequest)
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
		// Extract ID from path
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid task id", http.StatusBadRequest)
			return
		}

		var req struct {
			Title string `json:"title"`
			Done  bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}

		task, ok := store.Update(id, req.Title, req.Done)
		if !ok {
			http.Error(w, "task not found", http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("DELETE /tasks/", func(w http.ResponseWriter, r *http.Request) {
		// Extract ID from path
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid task id", http.StatusBadRequest)
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
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}

	var resp map[string]string
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}

	if resp["status"] != "ok" {
		t.Errorf("expected status ok, got %s", resp["status"])
	}
}

func TestCreateTask(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"Test task"}`))
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("expected status 201, got %d", w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("expected ID 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("expected title 'Test task', got %s", task.Title)
	}
	if task.Done {
		t.Errorf("expected done=false, got true")
	}
}

func TestGetTask(t *testing.T) {
	app := App()

	// First create a task
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"Get me"}`))
	createW := httptest.NewRecorder()
	app.ServeHTTP(createW, createReq)

	req := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	w := httptest.NewRecorder()
	app.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("expected ID 1, got %d", task.ID)
	}
	if task.Title != "Get me" {
		t.Errorf("expected title 'Get me', got %s", task.Title)
	}
}

func TestGetNonExistentTask(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("expected status 404, got %d", w.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	app := App()

	// First create a task
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"Delete me"}`))
	createW := httptest.NewRecorder()
	app.ServeHTTP(createW, createReq)

	// Delete the task
	deleteReq := httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
	deleteW := httptest.NewRecorder()
	app.ServeHTTP(deleteW, deleteReq)

	if deleteW.Code != http.StatusNoContent {
		t.Errorf("expected status 204, got %d", deleteW.Code)
	}

	// Verify deletion
	getReq := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	getW := httptest.NewRecorder()
	app.ServeHTTP(getW, getReq)

	if getW.Code != http.StatusNotFound {
		t.Errorf("expected status 404 after deletion, got %d", getW.Code)
	}
}
```