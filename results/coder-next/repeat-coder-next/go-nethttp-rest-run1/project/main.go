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

var (
	tasks     = make(map[uint64]Task)
	nextID    uint64 = 1
	taskMutex sync.RWMutex
)

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if data != nil {
		_ = json.NewEncoder(w).Encode(data)
	}
}

func App() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
		taskMutex.RLock()
		defer taskMutex.RUnlock()

		taskList := make([]Task, 0, len(tasks))
		for _, task := range tasks {
			taskList = append(taskList, task)
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(taskList)
	})

	mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Title string `json:"title"`
		}

		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON"})
			return
		}

		taskMutex.Lock()
		defer taskMutex.Unlock()

		task := Task{
			ID:    nextID,
			Title: req.Title,
			Done:  false,
		}
		tasks[task.ID] = task
		nextID++

		writeJSON(w, http.StatusCreated, task)
	})

	mux.HandleFunc("GET /tasks/", func(w http.ResponseWriter, r *http.Request) {
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid ID"})
			return
		}

		taskMutex.RLock()
		defer taskMutex.RUnlock()

		task, ok := tasks[id]
		if !ok {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "task not found"})
			return
		}

		writeJSON(w, http.StatusOK, task)
	})

	mux.HandleFunc("PUT /tasks/", func(w http.ResponseWriter, r *http.Request) {
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid ID"})
			return
		}

		var req struct {
			Title string `json:"title"`
			Done  bool   `json:"done"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON"})
			return
		}

		taskMutex.Lock()
		defer taskMutex.Unlock()

		task, ok := tasks[id]
		if !ok {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "task not found"})
			return
		}

		task.Title = req.Title
		task.Done = req.Done
		tasks[id] = task

		writeJSON(w, http.StatusOK, task)
	})

	mux.HandleFunc("DELETE /tasks/", func(w http.ResponseWriter, r *http.Request) {
		idStr := strings.TrimPrefix(r.URL.Path, "/tasks/")
		id, err := strconv.ParseUint(idStr, 10, 64)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid ID"})
			return
		}

		taskMutex.Lock()
		defer taskMutex.Unlock()

		if _, ok := tasks[id]; !ok {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "task not found"})
			return
		}

		delete(tasks, id)
		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

func main() {
	http.ListenAndServe(":3000", App())
}
