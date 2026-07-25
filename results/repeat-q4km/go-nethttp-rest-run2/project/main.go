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
