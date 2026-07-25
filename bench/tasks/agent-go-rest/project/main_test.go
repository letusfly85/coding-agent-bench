package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// do issues one request against a handler and returns the recorder.
// Note that each call to App() has its own state, so a scenario that spans
// several requests must reuse a single handler.
func do(t *testing.T, h http.Handler, method, path string, body string) *httptest.ResponseRecorder {
	t.Helper()
	var req *http.Request
	if body == "" {
		req = httptest.NewRequest(method, path, nil)
	} else {
		req = httptest.NewRequest(method, path, bytes.NewReader([]byte(body)))
		req.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

func TestHealth(t *testing.T) {
	w := do(t, App(), "GET", "/health", "")
	if w.Code != http.StatusOK {
		t.Fatalf("status: want 200, got %d", w.Code)
	}
	var got map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v (body %q)", err, w.Body.String())
	}
	if got["status"] != "ok" {
		t.Fatalf("body: want status=ok, got %v", got)
	}
}

func TestCreateReturns201AndID1(t *testing.T) {
	w := do(t, App(), "POST", "/tasks", `{"title":"first"}`)
	if w.Code != http.StatusCreated {
		t.Fatalf("status: want 201, got %d (body %q)", w.Code, w.Body.String())
	}
	var task Task
	if err := json.Unmarshal(w.Body.Bytes(), &task); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if task.ID != 1 || task.Title != "first" || task.Done {
		t.Fatalf("task: want {1 first false}, got %+v", task)
	}
}

func TestGetAfterCreate(t *testing.T) {
	h := App()
	do(t, h, "POST", "/tasks", `{"title":"get me"}`)

	w := do(t, h, "GET", "/tasks/1", "")
	if w.Code != http.StatusOK {
		t.Fatalf("status: want 200, got %d", w.Code)
	}
	var task Task
	if err := json.Unmarshal(w.Body.Bytes(), &task); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if task.Title != "get me" {
		t.Fatalf("title: want %q, got %q", "get me", task.Title)
	}
}

func TestGetMissingReturns404(t *testing.T) {
	w := do(t, App(), "GET", "/tasks/999", "")
	if w.Code != http.StatusNotFound {
		t.Fatalf("status: want 404, got %d", w.Code)
	}
}

func TestDeleteThenGetReturns404(t *testing.T) {
	h := App()
	do(t, h, "POST", "/tasks", `{"title":"delete me"}`)

	if w := do(t, h, "DELETE", "/tasks/1", ""); w.Code != http.StatusNoContent {
		t.Fatalf("delete status: want 204, got %d", w.Code)
	}
	if w := do(t, h, "GET", "/tasks/1", ""); w.Code != http.StatusNotFound {
		t.Fatalf("get after delete: want 404, got %d", w.Code)
	}
}

func TestListIsSortedByID(t *testing.T) {
	h := App()
	for _, title := range []string{"a", "b", "c"} {
		do(t, h, "POST", "/tasks", `{"title":"`+title+`"}`)
	}
	w := do(t, h, "GET", "/tasks", "")
	if w.Code != http.StatusOK {
		t.Fatalf("status: want 200, got %d", w.Code)
	}
	var tasks []Task
	if err := json.Unmarshal(w.Body.Bytes(), &tasks); err != nil {
		t.Fatalf("decode: %v (body %q)", err, w.Body.String())
	}
	if len(tasks) != 3 {
		t.Fatalf("len: want 3, got %d", len(tasks))
	}
	for i, want := range []uint64{1, 2, 3} {
		if tasks[i].ID != want {
			t.Fatalf("tasks[%d].id: want %d, got %d", i, want, tasks[i].ID)
		}
	}
}

func TestUpdate(t *testing.T) {
	h := App()
	do(t, h, "POST", "/tasks", `{"title":"before"}`)

	w := do(t, h, "PUT", "/tasks/1", `{"title":"after","done":true}`)
	if w.Code != http.StatusOK {
		t.Fatalf("status: want 200, got %d", w.Code)
	}
	var task Task
	if err := json.Unmarshal(w.Body.Bytes(), &task); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if task.Title != "after" || !task.Done {
		t.Fatalf("task: want {1 after true}, got %+v", task)
	}

	if w := do(t, h, "PUT", "/tasks/999", `{"title":"x","done":false}`); w.Code != http.StatusNotFound {
		t.Fatalf("update missing: want 404, got %d", w.Code)
	}
}
