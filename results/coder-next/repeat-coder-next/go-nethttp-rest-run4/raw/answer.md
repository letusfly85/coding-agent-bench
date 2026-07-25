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
)

type Task struct {
	ID    uint64 `json:"id"`
	Title string `json:"title"`
	Done  bool   `json:"done"`
}

type taskStore struct {
	mu    sync.RWMutex
	tasks map[uint64]Task
	next  uint64
}

var store *taskStore

func initStore() *taskStore {
	return &taskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}
}

func (s *taskStore) add(title string) Task {
	s.mu.Lock()
	defer s.mu.Unlock()
	task := Task{
		ID:    s.next,
		Title: title,
		Done:  false,
	}
	s.tasks[s.next] = task
	s.next++
	return task
}

func (s *taskStore) getAll() []Task {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]Task, 0, len(s.tasks))
	for i := uint64(1); i < s.next; i++ {
		if task, ok := s.tasks[i]; ok {
			result = append(result, task)
		}
	}
	return result
}

func (s *taskStore) get(id uint64) (Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	task, ok := s.tasks[id]
	return task, ok
}

func (s *taskStore) update(id uint64, title string, done bool) (Task, bool) {
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

func (s *taskStore) delete(id uint64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.tasks[id]; !ok {
		return false
	}
	delete(s.tasks, id)
	return true
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func tasksListHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(store.getAll())
}

func taskCreateHandler(w http.ResponseWriter, r *http.Request) {
	var input struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	task := store.add(input.Title)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(task)
}

func taskHandler(w http.ResponseWriter, r *http.Request) {
	// Extract ID from path: /tasks/{id}
	path := r.URL.Path
	idStr := path[len("/tasks/"):]
	if idStr == "" {
		http.Error(w, "missing task id", http.StatusBadRequest)
		return
	}
	id, err := strconv.ParseUint(idStr, 10, 64)
	if err != nil {
		http.Error(w, "invalid task id", http.StatusBadRequest)
		return
	}

	switch r.Method {
	case http.MethodGet:
		task, ok := store.get(id)
		if !ok {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(task)

	case http.MethodPut:
		var input struct {
			Title string `json:"title"`
			Done  bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			http.Error(w, "invalid json", http.StatusBadRequest)
			return
		}
		task, ok := store.update(id, input.Title, input.Done)
		if !ok {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(task)

	case http.MethodDelete:
		if ok := store.delete(id); !ok {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusNoContent)

	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func App() http.Handler {
	store = initStore()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", healthHandler)
	mux.HandleFunc("GET /tasks", tasksListHandler)
	mux.HandleFunc("POST /tasks", taskCreateHandler)
	mux.HandleFunc("GET /tasks/", taskHandler)
	mux.HandleFunc("PUT /tasks/", taskHandler)
	mux.HandleFunc("DELETE /tasks/", taskHandler)

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
	store = initStore()
	req := httptest.NewRequest("GET", "/health", nil)
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}
	var resp map[string]string
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if resp["status"] != "ok" {
		t.Errorf("expected {\"status\":\"ok\"}, got %v", resp)
	}
}

func TestCreateTask(t *testing.T) {
	store = initStore()
	input := bytes.NewBufferString(`{"title":"Test task"}`)
	req := httptest.NewRequest("POST", "/tasks", input)
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("expected status 201, got %d", w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("expected id 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("expected title 'Test task', got %s", task.Title)
	}
	if task.Done {
		t.Errorf("expected done to be false, got true")
	}
}

func TestGetTask(t *testing.T) {
	store = initStore()
	app := App()

	// First create a task
	input := bytes.NewBufferString(`{"title":"Get me"}`)
	req := httptest.NewRequest("POST", "/tasks", input)
	w := httptest.NewRecorder()
	app.ServeHTTP(w, req)

	// Then get it
	req = httptest.NewRequest("GET", "/tasks/1", nil)
	w = httptest.NewRecorder()
	app.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if task.ID != 1 || task.Title != "Get me" || task.Done {
		t.Errorf("unexpected task data: %+v", task)
	}
}

func TestGetNonExistentTask(t *testing.T) {
	store = initStore()
	req := httptest.NewRequest("GET", "/tasks/999", nil)
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("expected status 404, got %d", w.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	store = initStore()
	app := App()

	// Create task first
	input := bytes.NewBufferString(`{"title":"To be deleted"}`)
	req := httptest.NewRequest("POST", "/tasks", input)
	w := httptest.NewRecorder()
	app.ServeHTTP(w, req)

	// Delete it
	req = httptest.NewRequest("DELETE", "/tasks/1", nil)
	w = httptest.NewRecorder()
	app.ServeHTTP(w, req)

	if w.Code != http.StatusNoContent {
		t.Errorf("expected status 204, got %d", w.Code)
	}

	// Verify it's gone
	req = httptest.NewRequest("GET", "/tasks/1", nil)
	w = httptest.NewRecorder()
	app.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("after deletion, expected status 404, got %d", w.Code)
	}
}
```