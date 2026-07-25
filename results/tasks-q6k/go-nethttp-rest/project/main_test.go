package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestAPI(t *testing.T) {
	handler := App()

	t.Run("Health", func(t *testing.T) {
		req := httptest.NewRequest("GET", "/health", nil)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", w.Code)
		}
		var resp map[string]string
		if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
			t.Fatal(err)
		}
		if resp["status"] != "ok" {
			t.Fatalf("expected status ok, got %v", resp["status"])
		}
	})

	t.Run("CreateTask", func(t *testing.T) {
		body := strings.NewReader(`{"title":"Test Task"}`)
		req := httptest.NewRequest("POST", "/tasks", body)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)

		if w.Code != http.StatusCreated {
			t.Fatalf("expected 201, got %d", w.Code)
		}
		var task Task
		if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
			t.Fatal(err)
		}
		if task.ID != 1 {
			t.Fatalf("expected id 1, got %d", task.ID)
		}
	})

	t.Run("GetTask", func(t *testing.T) {
		req := httptest.NewRequest("GET", "/tasks/1", nil)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", w.Code)
		}
		var task Task
		if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
			t.Fatal(err)
		}
		if task.Title != "Test Task" {
			t.Fatalf("expected title 'Test Task', got %q", task.Title)
		}
	})

	t.Run("GetNonExistent", func(t *testing.T) {
		req := httptest.NewRequest("GET", "/tasks/999", nil)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)

		if w.Code != http.StatusNotFound {
			t.Fatalf("expected 404, got %d", w.Code)
		}
	})

	t.Run("DeleteTask", func(t *testing.T) {
		req := httptest.NewRequest("DELETE", "/tasks/1", nil)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusNoContent {
			t.Fatalf("expected 204 on delete, got %d", w.Code)
		}

		req2 := httptest.NewRequest("GET", "/tasks/1", nil)
		w2 := httptest.NewRecorder()
		handler.ServeHTTP(w2, req2)
		if w2.Code != http.StatusNotFound {
			t.Fatalf("expected 404 after delete, got %d", w2.Code)
		}
	})
}
