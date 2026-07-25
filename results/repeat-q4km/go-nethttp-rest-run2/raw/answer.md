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
	"strconv"
	"sync"
)

type Task struct {
	ID    uint64 `json:"id"`
	Title string `json:"title"`
	Done  bool   `json:"done"`
}

type taskStore struct {
	mu     sync.RWMutex
	tasks  map[uint64]*Task
	nextID uint64
}

func App() http.Handler {
	store := &taskStore{
		tasks:  make(map[uint64]*Task),
		nextID: 1,
	}

	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		store.mu.RLock()
		tasks := make([]*Task, 0, len(store.tasks))
		for _, t := range store.tasks {
			tasks = append(tasks, t)
		}
		store.mu.RUnlock()

		for i := 0; i < len(tasks); i++ {
			for j := i + 1; j < len(tasks); j++ {
				if tasks[i].ID > tasks[j].ID {
					tasks[i], tasks[j] = tasks[j], tasks[i]
				}
			}
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(tasks)
	})

	mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		store.mu.Lock()
		id := store.nextID
		store.nextID++
		task := &Task{ID: id, Title: body.Title, Done: false}
		store.tasks[id] = task
		store.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("GET /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		store.mu.RLock()
		task, ok := store.tasks[id]
		store.mu.RUnlock()

		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("PUT /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		var body Task
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		store.mu.Lock()
		task, ok := store.tasks[id]
		if !ok {
			store.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}
		task.Title = body.Title
		task.Done = body.Done
		store.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("DELETE /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		store.mu.Lock()
		_, ok := store.tasks[id]
		if !ok {
			store.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}
		delete(store.tasks, id)
		store.mu.Unlock()

		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

func main() {
	log.Fatal(http.ListenAndServe(":3000", App()))
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

func TestAPI(t *testing.T) {
	handler := App()

	t.Run("GET /health returns 200", func(t *testing.T) {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/health", nil)
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", rec.Code)
		}
	})

	t.Run("POST /tasks returns 201 and id 1", func(t *testing.T) {
		rec := httptest.NewRecorder()
		body := bytes.NewReader([]byte(`{"title":"Test Task"}`))
		req := httptest.NewRequest(http.MethodPost, "/tasks", body)
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
	})

	t.Run("GET /tasks/1 after creation returns the task", func(t *testing.T) {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
		handler.ServeHTTP(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", rec.Code)
		}

		var task Task
		if err := json.Unmarshal(rec.Body.Bytes(), &task); err != nil {
			t.Fatal(err)
		}
		if task.ID != 1 || task.Title != "Test Task" {
			t.Fatalf("unexpected task: %+v", task)
		}
	})

	t.Run("GET /tasks/999 returns 404", func(t *testing.T) {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
		handler.ServeHTTP(rec, req)

		if rec.Code != http.StatusNotFound {
			t.Fatalf("expected 404, got %d", rec.Code)
		}
	})

	t.Run("DELETE existing task returns 204 and subsequent GET returns 404", func(t *testing.T) {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
		handler.ServeHTTP(rec, req)

		if rec.Code != http.StatusNoContent {
			t.Fatalf("expected 204 on delete, got %d", rec.Code)
		}

		rec = httptest.NewRecorder()
		req = httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
		handler.ServeHTTP(rec, req)

		if rec.Code != http.StatusNotFound {
			t.Fatalf("expected 404 after delete, got %d", rec.Code)
		}
	})
}
```