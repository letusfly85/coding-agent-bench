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

func (s *taskStore) getAll() []Task {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]Task, 0, len(s.tasks))
	for id := uint64(1); id < s.nextID; id++ {
		if task, ok := s.tasks[id]; ok {
			result = append(result, task)
		}
	}
	return result
}

func (s *taskStore) create(title string) Task {
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

var store *taskStore

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

func tasksCreateHandler(w http.ResponseWriter, r *http.Request) {
	var input struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return
	}
	task := store.create(input.Title)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(task)
}

func taskHandler(w http.ResponseWriter, r *http.Request) {
	// Extract ID from path
	idStr := r.PathValue("id")
	if idStr == "" {
		http.Error(w, "missing ID", http.StatusBadRequest)
		return
	}
	id, err := strconv.ParseUint(idStr, 10, 64)
	if err != nil {
		http.Error(w, "invalid ID", http.StatusBadRequest)
		return
	}

	switch r.Method {
	case http.MethodGet:
		task, ok := store.get(id)
		if !ok {
			http.Error(w, "task not found", http.StatusNotFound)
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
			http.Error(w, "invalid JSON", http.StatusBadRequest)
			return
		}
		task, ok := store.update(id, input.Title, input.Done)
		if !ok {
			http.Error(w, "task not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(task)

	case http.MethodDelete:
		if !store.delete(id) {
			http.Error(w, "task not found", http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusNoContent)

	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func App() http.Handler {
	store = newTaskStore()
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", healthHandler)
	mux.HandleFunc("GET /tasks", tasksListHandler)
	mux.HandleFunc("POST /tasks", tasksCreateHandler)
	mux.HandleFunc("GET /tasks/{id}", taskHandler)
	mux.HandleFunc("PUT /tasks/{id}", taskHandler)
	mux.HandleFunc("DELETE /tasks/{id}", taskHandler)

	return mux
}

func main() {
	http.ListenAndServe(":3000", App())
}
