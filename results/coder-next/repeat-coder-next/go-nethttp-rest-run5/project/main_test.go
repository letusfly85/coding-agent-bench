package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealth(t *testing.T) {
	req := httptest.NewRequest("GET", "/health", nil)
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("Expected status %d, got %d", http.StatusOK, w.Code)
	}

	var result struct{ Status string }
	if err := json.NewDecoder(w.Body).Decode(&result); err != nil {
		t.Fatalf("Failed to decode response: %v", err)
	}
	if result.Status != "ok" {
		t.Errorf("Expected status 'ok', got '%s'", result.Status)
	}
}

func TestCreateTask(t *testing.T) {
	body := []byte(`{"title":"Test task"}`)
	req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("Expected status %d, got %d", http.StatusCreated, w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("Failed to decode response: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("Expected ID 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("Expected title 'Test task', got '%s'", task.Title)
	}
	if task.Done {
		t.Errorf("Expected done=false, got true")
	}
}

func TestGetTask(t *testing.T) {
	// Create a task first
	body := []byte(`{"title":"Get me"}`)
	req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Fatalf("Failed to create task: got status %d", w.Code)
	}

	// Now get the task
	req = httptest.NewRequest("GET", "/tasks/1", nil)
	w = httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("Expected status %d, got %d", http.StatusOK, w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("Failed to decode response: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("Expected ID 1, got %d", task.ID)
	}
	if task.Title != "Get me" {
		t.Errorf("Expected title 'Get me', got '%s'", task.Title)
	}
}

func TestGetTaskNotFound(t *testing.T) {
	req := httptest.NewRequest("GET", "/tasks/999", nil)
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("Expected status %d, got %d", http.StatusNotFound, w.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	// Create a task
	body := []byte(`{"title":"Delete me"}`)
	req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Fatalf("Failed to create task: got status %d", w.Code)
	}

	// Delete the task
	req = httptest.NewRequest("DELETE", "/tasks/1", nil)
	w = httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusNoContent {
		t.Errorf("Expected status %d, got %d", http.StatusNoContent, w.Code)
	}

	// Verify it's gone
	req = httptest.NewRequest("GET", "/tasks/1", nil)
	w = httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("After delete, expected status %d, got %d", http.StatusNotFound, w.Code)
	}
}
