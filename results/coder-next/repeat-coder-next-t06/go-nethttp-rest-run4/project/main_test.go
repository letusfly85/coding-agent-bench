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
		t.Errorf("expected status ok, got %s", resp["status"])
	}
}

func TestCreateTask(t *testing.T) {
	store = &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}

	body := []byte(`{"title":"Test task"}`)
	req := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewReader(body))
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
	if task.ID != 1 {
		t.Errorf("expected id 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("expected title 'Test task', got %s", task.Title)
	}
	if task.Done {
		t.Errorf("expected done false, got true")
	}
}

func TestGetTask(t *testing.T) {
	store = &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}

	// First create a task
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewReader([]byte(`{"title":"Get task"}`)))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	App().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: status %d", createRec.Code)
	}

	// Now get the task
	req := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rec.Code)
	}

	var task Task
	if err := json.NewDecoder(rec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if task.ID != 1 {
		t.Errorf("expected id 1, got %d", task.ID)
	}
	if task.Title != "Get task" {
		t.Errorf("expected title 'Get task', got %s", task.Title)
	}
}

func TestGetTaskNotFound(t *testing.T) {
	store = &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}

	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Errorf("expected status %d, got %d", http.StatusNotFound, rec.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	store = &TaskStore{
		tasks: make(map[uint64]Task),
		next:  1,
	}

	// Create a task first
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewReader([]byte(`{"title":"Delete me"}`)))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	App().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: status %d", createRec.Code)
	}

	// Delete the task
	deleteReq := httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
	deleteRec := httptest.NewRecorder()
	App().ServeHTTP(deleteRec, deleteReq)

	if deleteRec.Code != http.StatusNoContent {
		t.Errorf("expected status %d, got %d", http.StatusNoContent, deleteRec.Code)
	}

	// Verify it's gone
	req := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Errorf("expected status %d after delete, got %d", http.StatusNotFound, rec.Code)
	}
}
