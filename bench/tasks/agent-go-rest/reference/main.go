// Reference implementation. Not given to the model — it exists so the fixture
// can be shown to be solvable, and so main_test.go can be re-verified after edits.
//
//	cp reference/main.go project/main.go && (cd project && go test ./...)
package main

import (
	"encoding/json"
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

type store struct {
	mu     sync.RWMutex
	tasks  map[uint64]Task
	nextID uint64
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

func App() http.Handler {
	s := &store{tasks: map[uint64]Task{}, nextID: 1}
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		s.mu.RLock()
		out := make([]Task, 0, len(s.tasks))
		for _, t := range s.tasks {
			out = append(out, t)
		}
		s.mu.RUnlock()
		sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
		writeJSON(w, http.StatusOK, out)
	})

	mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		s.mu.Lock()
		t := Task{ID: s.nextID, Title: body.Title}
		s.tasks[t.ID] = t
		s.nextID++
		s.mu.Unlock()
		writeJSON(w, http.StatusCreated, t)
	})

	mux.HandleFunc("GET /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
		if err != nil {
			http.Error(w, "bad id", http.StatusBadRequest)
			return
		}
		s.mu.RLock()
		t, ok := s.tasks[id]
		s.mu.RUnlock()
		if !ok {
			http.NotFound(w, r)
			return
		}
		writeJSON(w, http.StatusOK, t)
	})

	mux.HandleFunc("PUT /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
		if err != nil {
			http.Error(w, "bad id", http.StatusBadRequest)
			return
		}
		var body struct {
			Title string `json:"title"`
			Done  bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		s.mu.Lock()
		_, ok := s.tasks[id]
		if ok {
			s.tasks[id] = Task{ID: id, Title: body.Title, Done: body.Done}
		}
		t := s.tasks[id]
		s.mu.Unlock()
		if !ok {
			http.NotFound(w, r)
			return
		}
		writeJSON(w, http.StatusOK, t)
	})

	mux.HandleFunc("DELETE /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
		if err != nil {
			http.Error(w, "bad id", http.StatusBadRequest)
			return
		}
		s.mu.Lock()
		_, ok := s.tasks[id]
		delete(s.tasks, id)
		s.mu.Unlock()
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

func main() {
	_ = http.ListenAndServe(":3000", App())
}
