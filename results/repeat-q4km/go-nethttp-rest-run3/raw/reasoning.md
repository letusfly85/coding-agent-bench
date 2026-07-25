Here's a thinking process:

1.  **Understand Requirements:**
   - Language: Go 1.22+
   - Standard library only
   - Module name: `task_api`
   - Use enhanced `http.ServeMux` routing patterns (Go 1.22+ features like `/tasks/{id}`)
   - Data model: `Task` with `ID`, `Title`, `Done`
   - In-memory state with `sync.RWMutex`
   - Expose `func App() http.Handler`
   - `main` listens on `:3000`
   - Endpoints:
     - GET `/health` -> 200 `{"status":"ok"}`
     - GET `/tasks` -> 200 JSON array (ascending id)
     - POST `/tasks` -> 201 with created task (id auto-increment, done=false)
     - GET `/tasks/{id}` -> 200 or 404
     - PUT `/tasks/{id}` -> 200 updated or 404
     - DELETE `/tasks/{id}` -> 204 or 404
   - Tests: `main_test.go` using `httptest.NewRecorder` and `App()`, covering 5 specific cases.
   - Output format: Strict. Only files with `### FILE: <path>` and code blocks. No commentary.

2.  **Design `go.mod`:**
   - Module name: `task_api`
   - Go version: 1.22
   - Simple:
     ```
     module task_api

     go 1.22
     ```

3.  **Design `main.go`:**
   - Needs:
     - `Task` struct
     - In-memory store: `tasks map[uint64]*Task`, `nextID uint64`, `mu sync.RWMutex`
     - `App()` function returning `http.Handler`
     - Routing using `http.NewServeMux()` with Go 1.22 patterns
     - Handlers for each endpoint
     - `main()` to listen on `:3000`
   - Let's draft the code mentally:

   ```go
   package main

   import (
       "encoding/json"
       "fmt"
       "log"
       "net/http"
       "sync"
   )

   type Task struct {
       ID    uint64 `json:"id"`
       Title string `json:"title"`
       Done  bool   `json:"done"`
   }

   type taskStore struct {
       mu     sync.RWMutex
       tasks  map[uint64]*Task
       nextID uint64
   }

   func newTaskStore() *taskStore {
       return &taskStore{
           tasks:  make(map[uint64]*Task),
           nextID: 1,
       }
   }

   func App() http.Handler {
       store := newTaskStore()
       mux := http.NewServeMux()

       mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
           w.Header().Set("Content-Type", "application/json")
           json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
       })

       mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
           store.mu.RLock()
           defer store.mu.RUnlock()

           // Sort tasks by ID
           ids := make([]uint64, 0, len(store.tasks))
           for id := range store.tasks {
               ids = append(ids, id)
           }
           // Simple sort since IDs are sequential
           for i := 0; i < len(ids); i++ {
               for j := i + 1; j < len(ids); j++ {
                   if ids[i] > ids[j] {
                       ids[i], ids[j] = ids[j], ids[i]
                   }
               }
           }

           tasks := make([]*Task, 0, len(ids))
           for _, id := range ids {
               tasks = append(tasks, store.tasks[id])
           }

           w.Header().Set("Content-Type", "application/json")
           json.NewEncoder(w).Encode(tasks)
       })

       mux.HandleFunc("POST /tasks", func(w http.ResponseWriter, r *http.Request) {
           var input struct {
               Title string `json:"title"`
           }
           if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
               http.Error(w, "invalid JSON", http.StatusBadRequest)
               return
           }

           store.mu.Lock()
           defer store.mu.Unlock()

           task := &Task{
               ID:    store.nextID,
               Title: input.Title,
               Done:  false,
           }
           store.nextID++
           store.tasks[task.ID] = task

           w.Header().Set("Content-Type", "application/json")
           w.WriteHeader(http.StatusCreated)
           json.NewEncoder(w).Encode(task)
       })

       mux.HandleFunc("GET /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
           id, err := parseID(r)
           if err != nil {
               http.Error(w, "invalid id", http.StatusBadRequest)
               return
           }

           store.mu.RLock()
           defer store.mu.RUnlock()

           task, ok := store.tasks[id]
           if !ok {
               http.NotFound(w, r)
               return
           }

           w.Header().Set("Content-Type", "application/json")
           json.NewEncoder(w).Encode(task)
       })

       mux.HandleFunc("PUT /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
           id, err := parseID(r)
           if err != nil {
               http.Error(w, "invalid id", http.StatusBadRequest)
               return
           }

           var input struct {
               Title *string `json:"title"`
               Done  *bool   `json:"done"`
           }
           if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
               http.Error(w, "invalid JSON", http.StatusBadRequest)
               return
           }

           store.mu.Lock()
           defer store.mu.Unlock()

           task, ok := store.tasks[id]
           if !ok {
               http.NotFound(w, r)
               return
           }

           if input.Title != nil {
               task.Title = *input.Title
           }
           if input.Done != nil {
               task.Done = *input.Done
           }

           w.Header().Set("Content-Type", "application/json")
           json.NewEncoder(w).Encode(task)
       })

       mux.HandleFunc("DELETE /tasks/{id}", func(w http.ResponseWriter, r *http.Request) {
           id, err := parseID(r)
           if err != nil {
               http.Error(w, "invalid id", http.StatusBadRequest)
               return
           }

           store.mu.Lock()
           defer store.mu.Unlock()

           if _, ok := store.tasks[id]; !ok {
               http.NotFound(w, r)
               return
           }

           delete(store.tasks, id)
           w.WriteHeader(http.StatusNoContent)
       })

       return mux
   }

   func parseID(r *http.Request) (uint64, error) {
       idStr := r.PathValue("id")
       var id uint64
       if _, err := fmt.Sscanf(idStr, "%d", &id); err != nil {
           return 0, err
       }
       return id, nil
   }

   func main() {
       log.Println("Starting server on :3000")
       if err := http.ListenAndServe(":3000", App()); err != nil {
           log.Fatal(err)
       }
   }
   ```

   Check constraints:
   - Go 1.22+ patterns: `mux.HandleFunc("GET /tasks/{id}", ...)` is correct.
   - `sync.RWMutex`: Used.
   - `App()` exposes handler with fresh state: Yes.
   - `main` listens on `:3000`: Yes.
   - `go vet`: Should pass. I'll double-check imports and usage.
   - JSON encoding: Using `json.NewEncoder(w).Encode(...)` which adds a newline. That's fine.
   - PUT handling: Using pointers for optional fields is good practice, but requirement says body `{"title":"...","done":true}`. I'll keep it simple: just decode into a struct with `Title` and `Done`, and update if provided. Actually, the requirement says "Body `{"title":"...","done":true}`", implying both might be sent. I'll just decode into a struct with `Title string` and `Done bool`, and update both. If they want partial updates, pointers are safer, but I'll stick to simple struct matching the requirement. Let's adjust to:
     ```go
     var input struct {
         Title string `json:"title"`
         Done  bool   `json:"done"`
     }
     ```
     And update:
     ```go
     if input.Title != "" { task.Title = input.Title }
     task.Done = input.Done // or keep as is? The requirement says "Body {...}", so I'll just assign directly.
     ```
     Actually, to be safe and standard, I'll just assign directly. It's a simple API.
     Let's refine PUT:
     ```go
     var input struct {
         Title string `json:"title"`
         Done  bool   `json:"done"`
     }
     // decode...
     task.Title = input.Title
     task.Done = input.Done
     ```
     This matches the requirement exactly.

   - Sorting: Since IDs are strictly incrementing, sorting by ID is just iterating from 1 to N or sorting the slice. I'll keep the simple sort or just use `slices.Sort` (Go 1.21+). I'll use `slices.Sort` for cleanliness, or keep the manual one. Actually, `slices` is standard in 1.21+, so I can import `slices`. Or just keep the manual bubble sort for simplicity, or sort by ID directly. I'll use `slices.SortFunc` or just a simple loop. I'll stick to a simple sort to avoid extra imports if not needed, but `slices` is fine. I'll just use a simple sort.

   Let's verify `go vet` compatibility:
   - `fmt.Sscanf` is fine.
   - `http.NotFound` is fine.
   - All handlers match method/path pattern.
   - No unused imports.
   - Struct tags match.
   - Looks solid.

4.  **Design `main_test.go`:**
   - Requirements:
     1. GET /health -> 200
     2. POST /tasks -> 201, id 1
     3. GET /tasks/1 -> returns task
     4. GET /tasks/999 -> 404
     5. DELETE existing -> 204, subsequent GET -> 404
   - Use `httptest.NewRecorder` and `App()`
   - No TCP port.

   Draft:
   ```go
   package main

   import (
       "bytes"
       "encoding/json"
       "net/http"
       "net/http/httptest"
       "testing"
   )

   func TestHealth(t *testing.T) {
       mux := App()
       req := httptest.NewRequest(http.MethodGet, "/health", nil)
       w := httptest.NewRecorder()
       mux.ServeHTTP(w, req)

       if w.Code != http.StatusOK {
           t.Fatalf("expected 200, got %d", w.Code)
       }
       var res map[string]string
       if err := json.Unmarshal(w.Body.Bytes(), &res); err != nil {
           t.Fatal(err)
       }
       if res["status"] != "ok" {
           t.Fatalf("expected status ok, got %v", res)
       }
   }

   func TestCreateTask(t *testing.T) {
       mux := App()
       body := bytes.NewBufferString(`{"title":"Test Task"}`)
       req := httptest.NewRequest(http.MethodPost, "/tasks", body)
       req.Header.Set("Content-Type", "application/json")
       w := httptest.NewRecorder()
       mux.ServeHTTP(w, req)

       if w.Code != http.StatusCreated {
           t.Fatalf("expected 201, got %d", w.Code)
       }

       var task Task
       if err := json.Unmarshal(w.Body.Bytes(), &task); err != nil {
           t.Fatal(err)
       }
       if task.ID != 1 {
           t.Fatalf("expected id 1, got %d", task.ID)
       }
   }

   func TestGetTask(t *testing.T) {
       mux := App()
       // Create task
       body := bytes.NewBufferString(`{"title":"Task 1"}`)
       req := httptest.NewRequest(http.MethodPost, "/tasks", body)
       req.Header.Set("Content-Type", "application/json")
       w := httptest.NewRecorder()
       mux.ServeHTTP(w, req)

       // Get task
       req = httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
       w = httptest.NewRecorder()
       mux.ServeHTTP(w, req)

       if w.Code != http.StatusOK {
           t.Fatalf("expected 200, got %d", w.Code)
       }

       var task Task
       json.Unmarshal(w.Body.Bytes(), &task)
       if task.ID != 1 || task.Title != "Task 1" {
           t.Fatalf("unexpected task: %+v", task)
       }
   }

   func TestGetNonExistentTask(t *testing.T) {
       mux := App()
       req := httptest.NewRequest(http.MethodGet, "/tasks/999", nil)
       w := httptest.NewRecorder()
       mux.ServeHTTP(w, req)

       if w.Code != http.StatusNotFound {
           t.Fatalf("expected 404, got %d", w.Code)
       }
   }

   func TestDeleteTask(t *testing.T) {
       mux := App()
       // Create task
       body := bytes.NewBufferString(`{"title":"To Delete"}`)
       req := httptest.NewRequest(http.MethodPost, "/tasks", body)
       req.Header.Set("Content-Type", "application/json")
       w := httptest.NewRecorder()
       mux.ServeHTTP(w, req)

       // Delete task
       req = httptest.NewRequest(http.MethodDelete, "/tasks/1", nil)
       w = httptest.NewRecorder()
       mux.ServeHTTP(w, req)
       if w.Code != http.StatusNoContent {
           t.Fatalf("expected 204 on delete, got %d", w.Code)
       }

       // Verify deleted
       req = httptest.NewRequest(http.MethodGet, "/tasks/1", nil)
       w = httptest.NewRecorder()
       mux.ServeHTTP(w, req)
       if w.Code != http.StatusNotFound {
           t.Fatalf("expected 404 after delete, got %d", w.Code)
       }
   }
   ```

   Check constraints:
   - Covers all 5 cases.
   - Uses `httptest.NewRecorder` and `App()`.
   - No TCP port.
   - Imports are standard.
   - Should pass `go vet`.

   One minor thing: `App()` creates a fresh mux/store each time, which is perfect for tests.
   The code looks solid.

   Let's double check the `PUT` endpoint requirement: "Body `{"title":"...","done":true}`. Returns 200 with the updated task, or 404 if absent."
   My implementation handles it. I'll make sure it's robust.

   Check `go vet` potential issues:
   - `fmt.Sscanf` with `%d` for `uint64` is fine.
   - `json.NewEncoder(w).Encode(...)` is fine.
   - No unused variables.
   - All good.

   One detail: Go 1.22 `http.ServeMux` pattern syntax:
   `mux.HandleFunc("GET /tasks/{id}", ...)` is correct.
   I'll ensure the code uses exactly that.

   Let's assemble the files exactly as requested.
   Format:
   ### FILE: go.mod
   ```
   ...
   ```
   ### FILE: main.go
   ```go
   ...
   ```
   ### FILE: main_test.go
   ```go
   ...
   ```

   I will verify the `GET /tasks` sorting requirement: "ordered by ascending id". Since IDs are sequential and we only delete, there might be gaps. My sort handles gaps correctly. I'll use `slices.Sort` for brevity and modern Go, but I'll stick to standard library. `slices` is in standard library since 1.21. I'll use it or just a simple sort. I'll keep the manual sort to avoid extra import if not strictly necessary, but `slices` is fine. Actually, I'll just use a simple loop or `sort.Slice` from `sort` package. `sort` is standard. I'll use `sort.Slice`.

   Revised `GET /tasks` handler:
   ```go
   mux.HandleFunc("GET /tasks", func(w http.ResponseWriter, r *http.Request) {
       store.mu.RLock()
       defer store.mu.RUnlock()

       ids := make([]uint64, 0, len(store.tasks))
       for id := range store.tasks {
           ids = append(ids, id)
       }
       sort.Slice(ids, func(i, j int) bool {
           return ids[i] < ids[j]
       })

       tasks := make([]*Task, 0, len(ids))
       for _, id := range ids {
           tasks = append(tasks, store.tasks[id])
       }

       w.Header().Set("Content-Type", "application/json")
       json.NewEncoder(w).Encode(tasks)
   })
   ```
   Need to import `"sort"`.

   Let's put it all together carefully.

   Check `main.go` imports:
   ```go
   import (
       "encoding/json"
       "fmt"
       "log"
       "net/http"
       "sort"
       "sync"
   )
   ```

   Check `main_test.go` imports:
   ```go
   import (
       "bytes"
       "encoding/json"
       "net/http"
       "net/http/httptest"
       "testing"
   )
   ```

   All good.

   One detail: `App()` should return `http.Handler`. `http.NewServeMux()` implements `http.Handler`, so returning it is correct.

   Let's verify the exact output format requirement:
   "Output **only** the files, each introduced by a `### FILE: <relative path>` line followed by a single fenced code block. No commentary before, between, or after the files."

   I will strictly follow this.

   Final check of the code:
   - `go.mod`: module task_api, go 1.22
   - `main.go`: matches all specs.
   - `main_test.go`: matches all specs.
   - Uses Go 1.22 routing patterns.
   - In-memory with RWMutex.
   - `App()` exposed.
   - `main` on :3000.
   - `go vet` safe.

   Ready. Output matches exactly.
   One minor thing: `fmt.Sscanf` might be considered slightly heavy for parsing ID, but it's fine. Alternatively, `strconv.ParseUint` is cleaner. I'll switch to `strconv.ParseUint` to be more idiomatic and avoid `fmt` import if not needed elsewhere. Actually, `fmt` is used in `log.Fatal`? No, `log.Fatal` takes fmt-style args, but I can use `log.Fatalf`. I'll keep `fmt` or use `log.Fatal(err)`. I'll use `strconv` for ID parsing.

   Revised `parseID`:
   ```go
   func parseID(r *http.Request) (uint64, error) {
       idStr := r.PathValue("id")
       return strconv.ParseUint(idStr, 10, 64)
   }
   ```
   Add `"strconv"` to imports. Remove `"fmt"` if unused. `log.Fatal` doesn't need `fmt`. Good.

   Let's assemble. 
   All constraints met. Output matches requirement.
   Proceed. 
   Self-Correction/Verification during thought:
   - `http.NotFound(w, r)` sets 404 and writes "404 page not found\n". That's fine.
   - `json.NewEncoder(w).Encode(...)` adds trailing newline. Standard.
   - `App()` creates fresh state. Correct.
   - Tests use `App()` directly. Correct.
   - Go 1.22 routing: `mux.HandleFunc("GET /tasks/{id}", ...)` is correct.
   - `sync.RWMutex` used correctly.
   - All good.
   Output matches exactly.✅
   Generating. 
   [Output Generation] -> matches the response. 
   Note: I'll make sure the code blocks are correctly formatted and no extra text is included.
   One thing: `go.mod` should just be:
   ```
   module task_api

   go 1.22
   ```
   That's it.
   All set. 
   Proceeds. 
   [Output Generation] (matches the final response)
   *(Self-Correction/Refinement during drafting)*: 
   Wait, `http.NotFound` writes to the response. If I call `json.NewEncoder(w).Encode(task)` later in other handlers, it's fine. But for 404, `http.NotFound` is standard.
   I'll stick with it.
   Also, `App()` returns `http.Handler`. `http.NewServeMux()` implements it.
   Everything is solid.
   Output matches exactly. 
   Proceeds. 
   [Final Check of the Prompt]: "Go 1.22 or newer (use the enhanced `http.ServeMux` routing patterns)." -> Checked.
   "Expose `func App() http.Handler` that builds the mux with fresh state, so tests can call it directly." -> Checked.
   "Must pass `go vet ./...`." -> Checked.
   "Include `main_test.go` driving the handler via `httptest.NewRecorder` and `App()`" -> Checked.
   All good.
   Output matches. 
   [Done] 
   *(Proceeds to generate response)*
