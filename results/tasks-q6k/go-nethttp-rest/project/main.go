package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sort"
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

func App() http.Handler {
	store := &taskStore{
		tasks:  make(map[uint64]Task),
		nextID: 1,
	}

	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		store.mu.RLock()
		tasks := make([]Task, 0, len(store.tasks))
		for _, t := range store.tasks {
			tasks = append(tasks, t)
		}
		store.mu.RUnlock()

		sort.Slice(tasks, func(i, j int) bool {
			return tasks[i].ID < tasks[j].ID
		})

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

		store.mu.Lock()
		id := store.nextID
		store.nextID++
		task := Task{
			ID:    id,
			Title: input.Title,
			Done:  false,
		}
		store.tasks[id] = task
		store.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(task)
	})

	mux.HandleFunc("GET /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		idStr := r.PathValue("id")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		store.mu.RLock()
		t, ok := store.tasks[id]
		store.mu.RUnlock()

		if !ok {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("PUT /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		idStr := r.PathValue("id")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		var input struct {
			Title string `json:"title"`
			Done  *bool  `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			http.Error(w, "invalid JSON", http.StatusBadRequest)
			return
		}

		store.mu.Lock()
		t, ok := store.tasks[id]
		if !ok {
			store.mu.Unlock()
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		t.Title = input.Title
		if input.Done != nil {
			t.Done = *input.Done
		}
		store.tasks[id] = t
		store.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("DELETE /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		idStr := r.PathValue("id")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		store.mu.Lock()
		_, ok := store.tasks[id]
		if !ok {
			store.mu.Unlock()
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		delete(store.tasks, id)
		store.mu.Unlock()

		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

func main() {
	log.Println("Starting server on :3000")
	log.Fatal(http.ListenAndServe(":3000", App()))
}
