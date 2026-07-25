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
	mu    sync.RWMutex
	tasks map[uint64]Task
	next  uint64
}

var store *TaskStore

func initStore() *TaskStore {
	return &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}
}

func App() http.Handler {
	store = initStore()
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", handleHealth)
	mux.HandleFunc("GET /tasks", handleGetTasks)
	mux.HandleFunc("POST /tasks", handleCreateTask)
	mux.HandleFunc("GET /tasks/", handleGetTask)
	mux.HandleFunc("PUT /tasks/", handleUpdateTask)
	mux.HandleFunc("DELETE /tasks/", handleDeleteTask)

	return mux
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

func handleGetTasks(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	store.mu.RLock()
	defer store.mu.RUnlock()

	tasks := make([]Task, 0, len(store.tasks))
	for _, task := range store.tasks {
		tasks = append(tasks, task)
	}
	// Already ordered by ID since we use uint64 and increment sequentially

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(tasks)
}

func handleCreateTask(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var input struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		http.Error(w, "Invalid JSON", http.StatusBadRequest)
		return
	}

	store.mu.Lock()
	defer store.mu.Unlock()

	task := Task{
		ID:    store.next,
		Title: input.Title,
		Done:  false,
	}
	store.tasks[task.ID] = task
	store.next++

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(task)
}

func handleGetTask(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
	id, err := strconv.ParseUint(idStr, 10, 64)
	if err != nil {
		http.Error(w, "Invalid ID", http.StatusBadRequest)
		return
	}

	store.mu.RLock()
	defer store.mu.RUnlock()

	task, ok := store.tasks[id]
	if !ok {
		http.Error(w, "Task not found", http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(task)
}

func handleUpdateTask(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
	id, err := strconv.ParseUint(idStr, 10, 64)
	if err != nil {
		http.Error(w, "Invalid ID", http.StatusBadRequest)
		return
	}

	var input Task
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		http.Error(w, "Invalid JSON", http.StatusBadRequest)
		return
	}

	store.mu.Lock()
	defer store.mu.Unlock()

	task, ok := store.tasks[id]
	if !ok {
		http.Error(w, "Task not found", http.StatusNotFound)
		return
	}

	task.Title = input.Title
	task.Done = input.Done
	store.tasks[id] = task

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(task)
}

func handleDeleteTask(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
	id, err := strconv.ParseUint(idStr, 10, 64)
	if err != nil {
		http.Error(w, "Invalid ID", http.StatusBadRequest)
		return
	}

	store.mu.Lock()
	defer store.mu.Unlock()

	if _, ok := store.tasks[id]; !ok {
		http.Error(w, "Task not found", http.StatusNotFound)
		return
	}

	delete(store.tasks, id)
	w.WriteHeader(http.StatusNoContent)
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
	req := httptest.NewRequest("GET", "/health", nil)
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("Expected status %d, got %d", http.StatusOK, w.Code)
	}

	var result struct{ Status string }
	if err := json.NewDecoder(w.Body).Decode(&result); err != nil {
		t.Fatalf("Failed to decode response: %v", err)
	}
	if result.Status != "ok" {
		t.Errorf("Expected status 'ok', got '%s'", result.Status)
	}
}

func TestCreateTask(t *testing.T) {
	body := []byte(`{"title":"Test task"}`)
	req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("Expected status %d, got %d", http.StatusCreated, w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("Failed to decode response: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("Expected ID 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("Expected title 'Test task', got '%s'", task.Title)
	}
	if task.Done {
		t.Errorf("Expected done=false, got true")
	}
}

func TestGetTask(t *testing.T) {
	// Create a task first
	body := []byte(`{"title":"Get me"}`)
	req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Fatalf("Failed to create task: got status %d", w.Code)
	}

	// Now get the task
	req = httptest.NewRequest("GET", "/tasks/1", nil)
	w = httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("Expected status %d, got %d", http.StatusOK, w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("Failed to decode response: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("Expected ID 1, got %d", task.ID)
	}
	if task.Title != "Get me" {
		t.Errorf("Expected title 'Get me', got '%s'", task.Title)
	}
}

func TestGetTaskNotFound(t *testing.T) {
	req := httptest.NewRequest("GET", "/tasks/999", nil)
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("Expected status %d, got %d", http.StatusNotFound, w.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	// Create a task
	body := []byte(`{"title":"Delete me"}`)
	req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Fatalf("Failed to create task: got status %d", w.Code)
	}

	// Delete the task
	req = httptest.NewRequest("DELETE", "/tasks/1", nil)
	w = httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusNoContent {
		t.Errorf("Expected status %d, got %d", http.StatusNoContent, w.Code)
	}

	// Verify it's gone
	req = httptest.NewRequest("GET", "/tasks/1", nil)
	w = httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("After delete, expected status %d, got %d", http.StatusNotFound, w.Code)
	}
}
```