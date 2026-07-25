package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
)

type Task struct {
	ID    uint64 `json:"id"`
	Title string `json:"title"`
	Done  bool   `json:"done"`
}

type store struct {
	mu     sync.RWMutex
	tasks  map[uint64]Task
	nextID atomic.Uint64
}

func newStore() *store {
	s := &store{
		tasks: make(map[uint64]Task),
	}
	s.nextID.Store(1)
	return s
}

func App() http.Handler {
	s := newStore()
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		s.mu.RLock()
		out := make([]Task, 0, len(s.tasks))
		for _, t := range s.tasks {
			out = append(out, t)
		}
		s.mu.RUnlock()

		sort.Slice(out, func(i, j int) bool {
			return out[i].ID < out[j].ID
		})

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(out)
	})

	mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
		var input struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if input.Title == "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		id := s.nextID.Add(1) - 1
		t := Task{
			ID:    id,
			Title: input.Title,
			Done:  false,
		}

		s.mu.Lock()
		s.tasks[id] = t
		s.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("GET /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.RLock()
		t, ok := s.tasks[id]
		s.mu.RUnlock()

		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("PUT /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		var input struct {
			Title *string `json:"title"`
			Done  *bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.Lock()
		t, ok := s.tasks[id]
		if !ok {
			s.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}

		if input.Title != nil {
			t.Title = *input.Title
		}
		if input.Done != nil {
			t.Done = *input.Done
		}

		s.tasks[id] = t
		s.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(t)
	})

	mux.HandleFunc("DELETE /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := parseUint64PathVar(r, "id")
		if id == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		s.mu.Lock()
		_, ok := s.tasks[id]
		if !ok {
			s.mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}

		delete(s.tasks, id)
		s.mu.Unlock()

		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

func parseUint64PathVar(r *http.Request, name string) uint64 {
	v := r.PathValue(name)
	id, err := strconv.ParseUint(v, 10, 64)
	if err != nil {
		return 0
	}
	return id
}

func main() {
	h := App()
	log.Println("Listening on :3000")
	if err := http.ListenAndServe(":3000", h); err != nil {
		log.Fatal(err)
	}
}
