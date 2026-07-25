package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealth(t *testing.T) {
	store = initStore()
	req := httptest.NewRequest("GET", "/health", nil)
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}
	var resp map[string]string
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if resp["status"] != "ok" {
		t.Errorf("expected {\"status\":\"ok\"}, got %v", resp)
	}
}

func TestCreateTask(t *testing.T) {
	store = initStore()
	input := bytes.NewBufferString(`{"title":"Test task"}`)
	req := httptest.NewRequest("POST", "/tasks", input)
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("expected status 201, got %d", w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("expected id 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("expected title 'Test task', got %s", task.Title)
	}
	if task.Done {
		t.Errorf("expected done to be false, got true")
	}
}

func TestGetTask(t *testing.T) {
	store = initStore()
	app := App()

	// First create a task
	input := bytes.NewBufferString(`{"title":"Get me"}`)
	req := httptest.NewRequest("POST", "/tasks", input)
	w := httptest.NewRecorder()
	app.ServeHTTP(w, req)

	// Then get it
	req = httptest.NewRequest("GET", "/tasks/1", nil)
	w = httptest.NewRecorder()
	app.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if task.ID != 1 || task.Title != "Get me" || task.Done {
		t.Errorf("unexpected task data: %+v", task)
	}
}

func TestGetNonExistentTask(t *testing.T) {
	store = initStore()
	req := httptest.NewRequest("GET", "/tasks/999", nil)
	w := httptest.NewRecorder()
	App().ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("expected status 404, got %d", w.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	store = initStore()
	app := App()

	// Create task first
	input := bytes.NewBufferString(`{"title":"To be deleted"}`)
	req := httptest.NewRequest("POST", "/tasks", input)
	w := httptest.NewRecorder()
	app.ServeHTTP(w, req)

	// Delete it
	req = httptest.NewRequest("DELETE", "/tasks/1", nil)
	w = httptest.NewRecorder()
	app.ServeHTTP(w, req)

	if w.Code != http.StatusNoContent {
		t.Errorf("expected status 204, got %d", w.Code)
	}

	// Verify it's gone
	req = httptest.NewRequest("GET", "/tasks/1", nil)
	w = httptest.NewRecorder()
	app.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("after deletion, expected status 404, got %d", w.Code)
	}
}
