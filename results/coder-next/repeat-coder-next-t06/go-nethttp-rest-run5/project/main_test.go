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
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}

	var resp map[string]string
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}

	if resp["status"] != "ok" {
		t.Errorf("expected status ok, got %s", resp["status"])
	}
}

func TestCreateTask(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"Test task"}`))
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("expected status 201, got %d", w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("expected ID 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("expected title 'Test task', got %s", task.Title)
	}
	if task.Done {
		t.Errorf("expected done=false, got true")
	}
}

func TestGetTask(t *testing.T) {
	app := App()

	// First create a task
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"Get me"}`))
	createW := httptest.NewRecorder()
	app.ServeHTTP(createW, createReq)

	req := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	w := httptest.NewRecorder()
	app.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}

	var task Task
	if err := json.NewDecoder(w.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("expected ID 1, got %d", task.ID)
	}
	if task.Title != "Get me" {
		t.Errorf("expected title 'Get me', got %s", task.Title)
	}
}

func TestGetNonExistentTask(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
	w := httptest.NewRecorder()

	App().ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("expected status 404, got %d", w.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	app := App()

	// First create a task
	createReq := httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewBufferString(`{"title":"Delete me"}`))
	createW := httptest.NewRecorder()
	app.ServeHTTP(createW, createReq)

	// Delete the task
	deleteReq := httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
	deleteW := httptest.NewRecorder()
	app.ServeHTTP(deleteW, deleteReq)

	if deleteW.Code != http.StatusNoContent {
		t.Errorf("expected status 204, got %d", deleteW.Code)
	}

	// Verify deletion
	getReq := httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
	getW := httptest.NewRecorder()
	app.ServeHTTP(getW, getReq)

	if getW.Code != http.StatusNotFound {
		t.Errorf("expected status 404 after deletion, got %d", getW.Code)
	}
}
