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

func init() {
	store = &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}
}

func App() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", healthHandler)
	mux.HandleFunc("GET /tasks", listTasksHandler)
	mux.HandleFunc("POST /tasks", createTaskHandler)
	mux.HandleFunc("GET /tasks/{id}", getTaskHandler)
	mux.HandleFunc("PUT /tasks/{id}", updateTaskHandler)
	mux.HandleFunc("DELETE /tasks/{id}", deleteTaskHandler)

	return mux
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func listTasksHandler(w http.ResponseWriter, r *http.Request) {
	store.mu.RLock()
	defer store.mu.RUnlock()

	tasks := make([]Task, 0, len(store.tasks))
	for id := uint64(1); id < store.next; id++ {
		if task, ok := store.tasks[id]; ok {
			tasks = append(tasks, task)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(tasks)
}

func createTaskHandler(w http.ResponseWriter, r *http.Request) {
	var input struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return
	}

	store.mu.Lock()
	defer store.mu.Unlock()

	task := Task{
		ID:    store.next,
		Title: input.Title,
		Done:  false,
	}
	store.tasks[store.next] = task
	store.next++

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(task)
}

func getTaskHandler(w http.ResponseWriter, r *http.Request) {
	idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
	id, err := strconv.ParseUint(idStr, 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	store.mu.RLock()
	defer store.mu.RUnlock()

	task, ok := store.tasks[id]
	if !ok {
		http.Error(w, "task not found", http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(task)
}

func updateTaskHandler(w http.ResponseWriter, r *http.Request) {
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

	store.mu.Lock()
	defer store.mu.Unlock()

	task, ok := store.tasks[id]
	if !ok {
		http.Error(w, "task not found", http.StatusNotFound)
		return
	}

	task.Title = input.Title
	task.Done = input.Done
	store.tasks[id] = task

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(task)
}

func deleteTaskHandler(w http.ResponseWriter, r *http.Request) {
	idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
	id, err := strconv.ParseUint(idStr, 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	store.mu.Lock()
	defer store.mu.Unlock()

	if _, ok := store.tasks[id]; !ok {
		http.Error(w, "task not found", http.StatusNotFound)
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
		t.Errorf("expected status ok, got %s", resp["status"])
	}
}

func TestCreateTask(t *testing.T) {
	store = &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}

	body := []byte(`{"title":"Test task"}`)
	req := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewReader(body))
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
	if task.ID != 1 {
		t.Errorf("expected id 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("expected title 'Test task', got %s", task.Title)
	}
	if task.Done {
		t.Errorf("expected done false, got true")
	}
}

func TestGetTask(t *testing.T) {
	store = &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}

	// First create a task
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewReader([]byte(`{"title":"Get task"}`)))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	App().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: status %d", createRec.Code)
	}

	// Now get the task
	req := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rec.Code)
	}

	var task Task
	if err := json.NewDecoder(rec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if task.ID != 1 {
		t.Errorf("expected id 1, got %d", task.ID)
	}
	if task.Title != "Get task" {
		t.Errorf("expected title 'Get task', got %s", task.Title)
	}
}

func TestGetTaskNotFound(t *testing.T) {
	store = &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}

	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Errorf("expected status %d, got %d", http.StatusNotFound, rec.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	store = &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}

	// Create a task first
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewReader([]byte(`{"title":"Delete me"}`)))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	App().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: status %d", createRec.Code)
	}

	// Delete the task
	deleteReq := httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
	deleteRec := httptest.NewRecorder()
	App().ServeHTTP(deleteRec, deleteReq)

	if deleteRec.Code != http.StatusNoContent {
		t.Errorf("expected status %d, got %d", http.StatusNoContent, deleteRec.Code)
	}

	// Verify it's gone
	req := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Errorf("expected status %d after delete, got %d", http.StatusNotFound, rec.Code)
	}
}
```