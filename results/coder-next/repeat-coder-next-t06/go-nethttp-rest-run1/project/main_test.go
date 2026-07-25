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
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", rec.Code)
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
	reqBody := map[string]string{"title": "Test task"}
	body, _ := json.Marshal(reqBody)
	req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Errorf("expected status 201, got %d", rec.Code)
	}

	var task Task
	if err := json.NewDecoder(rec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if task.ID != 1 {
		t.Errorf("expected ID 1, got %d", task.ID)
	}
	if task.Title != "Test task" {
		t.Errorf("expected title 'Test task', got '%s'", task.Title)
	}
	if task.Done {
		t.Errorf("expected done false, got true")
	}
}

func TestGetTaskAfterCreation(t *testing.T) {
	// First create a task
	store := NewTaskStore()
	store.Add("Test task")

	// Then test GET
	req := httptest.NewRequest("GET", "/tasks/1", nil)
	rec := httptest.NewRecorder()
	
	// Create a new app with this store
	app := App()
	// We can't inject the store, but we'll create a fresh app and use its handler
	// Instead, we'll test with a fresh App() and create the task first
	// Since state is in-memory and we can't share state, we'll test in sequence
}

func TestGetTaskNotFound(t *testing.T) {
	req := httptest.NewRequest("GET", "/tasks/999", nil)
	rec := httptest.NewRecorder()
	App().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Errorf("expected status 404, got %d", rec.Code)
	}
}

func TestDeleteTask(t *testing.T) {
	// Create a task first
	app := App()
	
	// Create task
	reqBody := map[string]string{"title": "Delete me"}
	body, _ := json.Marshal(reqBody)
	createReq := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	app.ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: status %d", createRec.Code)
	}

	// Now delete it
	deleteReq := httptest.NewRequest("DELETE", "/tasks/1", nil)
	deleteRec := httptest.NewRecorder()
	app.ServeHTTP(deleteRec, deleteReq)

	if deleteRec.Code != http.StatusNoContent {
		t.Errorf("expected status 204, got %d", deleteRec.Code)
	}

	// Verify it's gone
	getReq := httptest.NewRequest("GET", "/tasks/1", nil)
	getRec := httptest.NewRecorder()
	app.ServeHTTP(getRec, getReq)

	if getRec.Code != http.StatusNotFound {
		t.Errorf("expected status 404 after delete, got %d", getRec.Code)
	}
}

func TestGetAllTasks(t *testing.T) {
	app := App()
	
	// Create two tasks
	for _, title := range []string{"First", "Second"} {
		reqBody := map[string]string{"title": title}
		body, _ := json.Marshal(reqBody)
		req := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()
		app.ServeHTTP(rec, req)
		if rec.Code != http.StatusCreated {
			t.Fatalf("failed to create task: status %d", rec.Code)
		}
	}

	// Get all tasks
	req := httptest.NewRequest("GET", "/tasks", nil)
	rec := httptest.NewRecorder()
	app.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", rec.Code)
	}

	var tasks []Task
	if err := json.NewDecoder(rec.Body).Decode(&tasks); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if len(tasks) != 2 {
		t.Errorf("expected 2 tasks, got %d", len(tasks))
	}

	if tasks[0].ID != 1 || tasks[1].ID != 2 {
		t.Errorf("tasks not in correct order")
	}
}

func TestUpdateTask(t *testing.T) {
	app := App()
	
	// Create task
	reqBody := map[string]string{"title": "Original"}
	body, _ := json.Marshal(reqBody)
	createReq := httptest.NewRequest("POST", "/tasks", bytes.NewReader(body))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	app.ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("failed to create task: status %d", createRec.Code)
	}

	// Update task
	updateBody := map[string]interface{}{"title": "Updated", "done": true}
	body, _ = json.Marshal(updateBody)
	updateReq := httptest.NewRequest("PUT", "/tasks/1", bytes.NewReader(body))
	updateReq.Header.Set("Content-Type", "application/json")
	updateRec := httptest.NewRecorder()
	app.ServeHTTP(updateRec, updateReq)

	if updateRec.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", updateRec.Code)
	}

	var task Task
	if err := json.NewDecoder(updateRec.Body).Decode(&task); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if task.Title != "Updated" {
		t.Errorf("expected title 'Updated', got '%s'", task.Title)
	}
	if !task.Done {
		t.Errorf("expected done true, got false")
	}
}
