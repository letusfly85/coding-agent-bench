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
