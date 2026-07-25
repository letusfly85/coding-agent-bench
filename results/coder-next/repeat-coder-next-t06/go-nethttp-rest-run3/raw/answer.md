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

type taskStore struct {
	mu     sync.RWMutex
	tasks  map[uint64]Task
	nextID uint64
}

func newTaskStore() *taskStore {
	return &taskStore{
		tasks:  make(map[uint64]Task),
		nextID: 1,
	}
}

func (s *taskStore) addTask(title string) Task {
	s.mu.Lock()
	defer s.mu.Unlock()

	task := Task{
		ID:    s.nextID,
		Title: title,
		Done:  false,
	}
	s.tasks[task.ID] = task
	s.nextID++
	return task
}

func (s *taskStore) getTasks() []Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	tasks := make([]Task, 0, len(s.tasks))
	for id := uint64(1); id < s.nextID; id++ {
		if task, ok := s.tasks[id]; ok {
			tasks = append(tasks, task)
		}
	}
	return tasks
}

func (s *taskStore) getTask(id uint64) (Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	task, ok := s.tasks[id]
	return task, ok
}

func (s *taskStore) updateTask(id uint64, title string, done bool) (Task, bool) {
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

func (s *taskStore) deleteTask(id uint64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.tasks[id]; !ok {
		return false
	}
	delete(s.tasks, id)
	return true
}

func App() http.Handler {
	store := newTaskStore()

	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		tasks := store.getTasks()
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

		task := store.addTask(input.Title)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("GET /tasks/", func(w http.ResponseWriter, r *http.Request) {
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		task, ok := store.getTask(id)
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
			http.Error(w, "invalid id", http.StatusBadRequest)
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

		task, ok := store.updateTask(id, input.Title, input.Done)
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
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		if !store.deleteTask(id) {
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
		t.Errorf("expected status %d, got %d", http.StatusOK, w.Code)
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
	req := httptest.NewRequest(http.MethodPost, "/tasks", nil)
	req.Body = nil
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)
	if w.Code != http.StatusCreated {
		t.Errorf("expected status %d, got %d", http.StatusCreated, w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
	if task.ID != 1 {
		t.Errorf("expected id 1, got %d", task.ID)
	}
	if task.Done != false {
		t.Errorf("expected done false, got %v", task.Done)
	}
}

func TestGetTaskAfterCreate(t *testing.T) {
	store := newTaskStore()
	store.addTask("Test task")

	req := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	w := httptest.NewRecorder()

	// Create a new mux with the store's state
	mux := http.NewServeMux()
	mux.HandleFunc("GET /tasks/", func(w http.ResponseWriter, r *http.Request) {
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		task, ok := store.getTask(id)
		if !ok {
			http.Error(w, "task not found", http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(task)
	})

	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
	if task.ID != 1 {
		t.Errorf("expected id 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("expected title 'Test task', got %s", task.Title)
	}
}

func TestGetNonExistentTask(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("expected status %d, got %d", http.StatusNotFound, w.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	app := App()

	// Create a task first
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", nil)
	createReq.Body = nil
	createW := httptest.NewRecorder()
	app.ServeHTTP(createW, createReq)

	if createW.Code != http.StatusCreated {
		t.Fatalf("failed to create task: status %d", createW.Code)
	}

	// Delete the task
	deleteReq := httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
	deleteW := httptest.NewRecorder()
	app.ServeHTTP(deleteW, deleteReq)

	if deleteW.Code != http.StatusNoContent {
		t.Errorf("expected status %d, got %d", http.StatusNoContent, deleteW.Code)
	}

	// Verify task is gone
	getReq := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	getW := httptest.NewRecorder()
	app.ServeHTTP(getW, getReq)

	if getW.Code != http.StatusNotFound {
		t.Errorf("expected status %d after delete, got %d", http.StatusNotFound, getW.Code)
	}
}
```