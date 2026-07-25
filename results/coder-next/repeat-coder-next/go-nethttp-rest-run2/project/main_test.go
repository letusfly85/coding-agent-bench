package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealth(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rec.Code)
	}

	var resp map[string]string
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if resp["status"] != "ok" {
		t.Errorf("expected status 'ok', got '%s'", resp["status"])
	}
}

func TestCreateTask(t *testing.T) {
	store := NewTaskStore()
	// We'll test through App() which creates its own store, so we can't verify
	// internal state. Instead, we rely on subsequent requests.
	req := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"test"}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Errorf("expected status %d, got %d", http.StatusCreated, rec.Code)
	}

	var task Task
	if err := json.NewDecoder(rec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if task.Title != "test" {
		t.Errorf("expected title 'test', got '%s'", task.Title)
	}
	if task.Done {
		t.Error("expected done to be false")
	}
	if task.ID != 1 {
		t.Errorf("expected ID 1, got %d", task.ID)
	}
}

func TestGetTask(t *testing.T) {
	// First create a task
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"get me"}`))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	App().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: got status %d", createRec.Code)
	}

	// Then get it
	getReq := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	getRec := httptest.NewRecorder()
	App().ServeHTTP(getRec, getReq)

	if getRec.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, getRec.Code)
	}

	var task Task
	if err := json.NewDecoder(getRec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if task.Title != "get me" {
		t.Errorf("expected title 'get me', got '%s'", task.Title)
	}
}

func TestGetTaskNotFound(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Errorf("expected status %d, got %d", http.StatusNotFound, rec.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	// Create task first
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"delete me"}`))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	App().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: got status %d", createRec.Code)
	}

	// Delete it
	deleteReq := httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
	deleteRec := httptest.NewRecorder()
	App().ServeHTTP(deleteRec, deleteReq)

	if deleteRec.Code != http.StatusNoContent {
		t.Errorf("expected status %d, got %d", http.StatusNoContent, deleteRec.Code)
	}

	// Verify it's gone
	getReq := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	getRec := httptest.NewRecorder()
	App().ServeHTTP(getRec, getReq)

	if getRec.Code != http.StatusNotFound {
		t.Errorf("expected status %d after deletion, got %d", http.StatusNotFound, getRec.Code)
	}
}
