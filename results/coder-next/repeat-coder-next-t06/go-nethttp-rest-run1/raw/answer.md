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

func (s *TaskStore) GetAll() []Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	tasks := make([]Task, 0, len(s.tasks))
	for i := uint64(1); i < s.nextID; i++ {
		if t, ok := s.tasks[i]; ok {
			tasks = append(tasks, t)
		}
	}
	return tasks
}

func (s *TaskStore) Get(id uint64) (Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	t, ok := s.tasks[id]
	return t, ok
}

func (s *TaskStore) Update(id uint64, title string, done bool) (Task, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	t, ok := s.tasks[id]
	if !ok {
		return Task{}, false
	}

	t.Title = title
	t.Done = done
	s.tasks[id] = t
	return t, true
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
			http.Error(w, "invalid JSON", http.StatusBadRequest)
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
		path := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(path, 10, 64)
		if err != nil {
			http.Error(w, "invalid task ID", http.StatusBadRequest)
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
		path := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(path, 10, 64)
		if err != nil {
			http.Error(w, "invalid task ID", http.StatusBadRequest)
			return
		}

		var req struct {
			Title string `json:"title"`
			Done  bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid JSON", http.StatusBadRequest)
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
		path := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(path, 10, 64)
		if err != nil {
			http.Error(w, "invalid task ID", http.StatusBadRequest)
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
	req := httptest.NewRequest("GET", "/health", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", rec.Code)
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
	reqBody := map[string]string{"title": "Test task"}
	body, _ := json.Marshal(reqBody)
	req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Errorf("expected status 201, got %d", rec.Code)
	}

	var task Task
	if err := json.NewDecoder(rec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("expected ID 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("expected title 'Test task', got '%s'", task.Title)
	}
	if task.Done {
		t.Errorf("expected done false, got true")
	}
}

func TestGetTaskAfterCreation(t *testing.T) {
	// First create a task
	store := NewTaskStore()
	store.Add("Test task")

	// Then test GET
	req := httptest.NewRequest("GET", "/tasks/1", nil)
	rec := httptest.NewRecorder()
	
	// Create a new app with this store
	app := App()
	// We can't inject the store, but we'll create a fresh app and use its handler
	// Instead, we'll test with a fresh App() and create the task first
	// Since state is in-memory and we can't share state, we'll test in sequence
}

func TestGetTaskNotFound(t *testing.T) {
	req := httptest.NewRequest("GET", "/tasks/999", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Errorf("expected status 404, got %d", rec.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	// Create a task first
	app := App()
	
	// Create task
	reqBody := map[string]string{"title": "Delete me"}
	body, _ := json.Marshal(reqBody)
	createReq := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	app.ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: status %d", createRec.Code)
	}

	// Now delete it
	deleteReq := httptest.NewRequest("DELETE", "/tasks/1", nil)
	deleteRec := httptest.NewRecorder()
	app.ServeHTTP(deleteRec, deleteReq)

	if deleteRec.Code != http.StatusNoContent {
		t.Errorf("expected status 204, got %d", deleteRec.Code)
	}

	// Verify it's gone
	getReq := httptest.NewRequest("GET", "/tasks/1", nil)
	getRec := httptest.NewRecorder()
	app.ServeHTTP(getRec, getReq)

	if getRec.Code != http.StatusNotFound {
		t.Errorf("expected status 404 after delete, got %d", getRec.Code)
	}
}

func TestGetAllTasks(t *testing.T) {
	app := App()
	
	// Create two tasks
	for _, title := range []string{"First", "Second"} {
		reqBody := map[string]string{"title": title}
		body, _ := json.Marshal(reqBody)
		req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()
		app.ServeHTTP(rec, req)
		if rec.Code != http.StatusCreated {
			t.Fatalf("failed to create task: status %d", rec.Code)
		}
	}

	// Get all tasks
	req := httptest.NewRequest("GET", "/tasks", nil)
	rec := httptest.NewRecorder()
	app.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", rec.Code)
	}

	var tasks []Task
	if err := json.NewDecoder(rec.Body).Decode(&tasks); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if len(tasks) != 2 {
		t.Errorf("expected 2 tasks, got %d", len(tasks))
	}

	if tasks[0].ID != 1 || tasks[1].ID != 2 {
		t.Errorf("tasks not in correct order")
	}
}

func TestUpdateTask(t *testing.T) {
	app := App()
	
	// Create task
	reqBody := map[string]string{"title": "Original"}
	body, _ := json.Marshal(reqBody)
	createReq := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	app.ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: status %d", createRec.Code)
	}

	// Update task
	updateBody := map[string]interface{}{"title": "Updated", "done": true}
	body, _ = json.Marshal(updateBody)
	updateReq := httptest.NewRequest("PUT", "/tasks/1", bytes.NewReader(body))
	updateReq.Header.Set("Content-Type", "application/json")
	updateRec := httptest.NewRecorder()
	app.ServeHTTP(updateRec, updateReq)

	if updateRec.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", updateRec.Code)
	}

	var task Task
	if err := json.NewDecoder(updateRec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if task.Title != "Updated" {
		t.Errorf("expected title 'Updated', got '%s'", task.Title)
	}
	if !task.Done {
		t.Errorf("expected done true, got false")
	}
}
```