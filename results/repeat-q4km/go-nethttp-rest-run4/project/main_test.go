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

	// 1. GET /health returns 200
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /health: expected 200, got %d", rec.Code)
	}

	// 2. POST /tasks returns 201 and id 1
	body := []byte(`{"title":"Test Task"}`)
	req = httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("POST /tasks: expected 201, got %d", rec.Code)
	}
	var created Task
	if err := json.Unmarshal(rec.Body.Bytes(), &created); err != nil {
		t.Fatalf("failed to parse created task: %v", err)
	}
	if created.ID != 1 {
		t.Fatalf("POST /tasks: expected id 1, got %d", created.ID)
	}

	// 3. GET /tasks/1 after creation returns the task
	req = httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /tasks/1: expected 200, got %d", rec.Code)
	}
	var fetched Task
	if err := json.Unmarshal(rec.Body.Bytes(), &fetched); err != nil {
		t.Fatalf("failed to parse fetched task: %v", err)
	}
	if fetched.ID != 1 || fetched.Title != "Test Task" {
		t.Fatalf("GET /tasks/1: unexpected task: %+v", fetched)
	}

	// 4. GET /tasks/999 returns 404
	req = httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("GET /tasks/999: expected 404, got %d", rec.Code)
	}

	// 5. DELETE an existing task returns 204, and a subsequent GET returns 404
	req = httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("DELETE /tasks/1: expected 204, got %d", rec.Code)
	}

	req = httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("GET /tasks/1 after delete: expected 404, got %d", rec.Code)
	}
}
