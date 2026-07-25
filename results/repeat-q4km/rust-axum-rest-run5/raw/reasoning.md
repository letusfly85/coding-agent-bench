Here's a thinking process:

1.  **Understand the Goal**: The user wants a small REST API in Rust using `axum` with specific dependencies, endpoints, data model, constraints, and tests. Output format is strictly defined.

2.  **Analyze Requirements**:
   - Crate name: `task_api`
   - Dependencies (exact versions):
     - `axum = "0.8"`
     - `tokio = { version = "1", features = ["full"] }`
     - `serde = { version = "1", features = ["derive"] }`
     - `serde_json = "1"`
     - dev: `tower = { version = "0.5", features = ["util"] }`
     - dev: `http-body-util = "0.1"`
   - Data model: `Task { id: u64, title: String, done: bool }`
   - ID assignment: server-assigned, starts at 1, increments.
   - Endpoints:
     - `GET /health` -> 200 `{"status":"ok"}`
     - `GET /tasks` -> 200 JSON array, ordered by id asc
     - `POST /tasks` -> body `{"title":"..."}`, `done=false`, returns 201 with created task
     - `GET /tasks/{id}` -> 200 task or 404
     - `PUT /tasks/{id}` -> body `{"title":"...","done":true}`, returns 200 updated or 404
     - `DELETE /tasks/{id}` -> 204 if deleted, 404 if absent
   - Implementation constraints:
     - In-memory state only, `Arc<RwLock<...>>`
     - `pub fn app() -> axum::Router` builds router with fresh state for tests
     - `main` binds to `0.0.0.0:3000` and serves `app()`
     - Compile with no warnings
   - Tests:
     - In `src/main.rs` under `#[cfg(test)] mod tests`
     - Use `tower::ServiceExt::oneshot`
     - No real TCP port
     - Cover: health, POST creates id 1, GET /tasks/1, GET /tasks/999 -> 404, DELETE existing -> 204 then GET -> 404

3.  **Design State**:
   - Need a struct to hold tasks and the next ID.
   - `struct AppState { tasks: RwLock<BTreeMap<u64, Task>>, next_id: RwLock<u64> }` or combine into one lock. Simpler: `RwLock<AppStateInner>` where `AppStateInner` has `tasks: BTreeMap<u64, Task>` and `next_id: u64`.
   - Using `std::sync::RwLock` or `tokio::sync::RwLock`. I'll use `std::sync::RwLock` for simplicity, but `tokio::sync` is fine too. The prompt says "from std::sync or tokio::sync". I'll use `std::sync::RwLock`.

   Actually, `BTreeMap` keeps tasks sorted by id, which satisfies the "ordered by ascending id" requirement for `GET /tasks`.

4.  **Design Router & Handlers**:
   - `app()` creates `Arc<RwLock<AppStateInner>>` and sets up routes.
   - Handlers:
     - `health_handler`: returns `Json(serde_json::json!({"status": "ok"}))`
     - `list_tasks`: read lock, collect values, return `Json(tasks)`
     - `create_task`: extract `CreateTaskReq { title: String }`, read/write lock, assign id, insert, return `StatusCode::CREATED` and `Json(task)`
     - `get_task`: extract id, read lock, find, return 200 or 404
     - `update_task`: extract id, extract `UpdateTaskReq { title: String, done: bool }`, write lock, find, update, return 200 or 404
     - `delete_task`: extract id, write lock, remove, return 204 or 404

   Let's define request/response structs:
   ```rust
   #[derive(serde::Deserialize)]
   struct CreateTaskReq { title: String }

   #[derive(serde::Deserialize)]
   struct UpdateTaskReq { title: String, done: bool }

   #[derive(serde::Serialize, serde::Deserialize)]
   struct Task {
       id: u64,
       title: String,
       done: bool,
   }
   ```

   State:
   ```rust
   use std::sync::{Arc, RwLock};
   use std::collections::BTreeMap;

   struct AppState {
       tasks: BTreeMap<u64, Task>,
       next_id: u64,
   }
   ```

   Router:
   ```rust
   pub fn app() -> Router {
       let state = Arc::new(RwLock::new(AppState {
           tasks: BTreeMap::new(),
           next_id: 1,
       }));

       Router::new()
           .route("/health", get(health_handler))
           .route("/tasks", get(list_tasks).post(create_task))
           .route("/tasks/{id}", get(get_task).put(update_task).delete(delete_task))
           .with_state(state)
   }
   ```

   Handlers implementation details:
   - `health_handler`: `async fn health_handler() -> Json<serde_json::Value> { Json(serde_json::json!({"status": "ok"})) }`
   - `list_tasks`: `async fn list_tasks(State(state): State<Arc<RwLock<AppState>>>) -> Json<Vec<Task>> { let guard = state.read().unwrap(); let tasks = guard.tasks.values().cloned().collect(); Json(tasks) }`
   - `create_task`: `async fn create_task(State(state): State<Arc<RwLock<AppState>>>, Json(req): Json<CreateTaskReq>) -> (StatusCode, Json<Task>) { let mut guard = state.write().unwrap(); let id = guard.next_id; guard.next_id += 1; let task = Task { id, title: req.title, done: false }; guard.tasks.insert(id, task.clone()); (StatusCode::CREATED, Json(task)) }`
   - `get_task`: `async fn get_task(Path(id): Path<u64>, State(state): State<Arc<RwLock<AppState>>>) -> Result<Json<Task>, (StatusCode, String)> { let guard = state.read().unwrap(); match guard.tasks.get(&id).cloned() { Some(t) => Ok(Json(t)), None => Err((StatusCode::NOT_FOUND, "not found".into())) } }`
   - `update_task`: `async fn update_task(Path(id): Path<u64>, State(state): State<Arc<RwLock<AppState>>>, Json(req): Json<UpdateTaskReq>) -> Result<Json<Task>, (StatusCode, String)> { let mut guard = state.write().unwrap(); match guard.tasks.get_mut(&id) { Some(t) => { t.title = req.title; t.done = req.done; Ok(Json(t.clone())) } None => Err((StatusCode::NOT_FOUND, "not found".into())) } }`
   - `delete_task`: `async fn delete_task(Path(id): Path<u64>, State(state): State<Arc<RwLock<AppState>>>) -> Result<StatusCode, (StatusCode, String)> { let mut guard = state.write().unwrap(); if guard.tasks.remove(&id).is_some() { Ok(StatusCode::NO_CONTENT) } else { Err((StatusCode::NOT_FOUND, "not found".into())) } }`

   Check axum 0.8 API:
   - `Router::new()`
   - `.route()`
   - `.with_state()`
   - Handlers use `State<T>` extractor.
   - `Json<T>` extractor/response.
   - `Path<T>` extractor.
   - `StatusCode` from `axum::http`.
   - All standard.

5.  **Tests**:
   - Need to use `tower::ServiceExt::oneshot`.
   - Create router with `app()`.
   - Make requests using `http::Request`.
   - Use `http_body_util::BodyExt` or similar? The prompt says `http-body-util = "0.1"`. In axum 0.8 / tower 0.5, we can use `http_body_util::BodyExt::collect` or similar. Actually, `tower::ServiceExt::oneshot` returns `(Result<Response>)`. We can extract status and body.
   - Let's check typical pattern for axum testing with tower:
     ```rust
     use tower::ServiceExt;
     use http_body_util::BodyExt;

     #[tokio::test]
     async fn test_health() {
         let app = app();
         let req = Request::builder().uri("/health").body(Body::empty()).unwrap();
         let resp = app.oneshot(req).await.unwrap();
         assert_eq!(resp.status(), 200);
         // check body if needed
     }
     ```
   - Actually, `http_body_util` is needed to consume the body. We'll use `Body::from("")` or `Body::empty()`. Wait, `axum::body::Body` is used. In tests, we can construct `Request<axum::body::Body>`.
   - Let's use `axum::http::Request` and `axum::body::Body`.
   - To read body: `let body_bytes = resp.into_body().collect().await.unwrap().to_bytes();`
   - Then parse JSON with `serde_json`.

   I'll write out the test cases carefully.

   Test 1: GET /health -> 200
   Test 2: POST /tasks -> 201, id 1
   Test 3: GET /tasks/1 -> 200, same task
   Test 4: GET /tasks/999 -> 404
   Test 5: DELETE /tasks/1 -> 204, then GET /tasks/1 -> 404

   All in one test or separate? Separate is cleaner.

   Let's draft test code:
   ```rust
   #[cfg(test)]
   mod tests {
       use super::*;
       use axum::http::Request;
       use axum::body::Body;
       use tower::ServiceExt;
       use http_body_util::BodyExt;

       async fn send_req(app: Router, req: Request<Body>) -> (axum::http::StatusCode, String) {
           let resp = app.oneshot(req).await.unwrap();
           let status = resp.status();
           let body_bytes = resp.into_body().collect().await.unwrap().to_bytes();
           let body_str = String::from_utf8_lossy(&body_bytes).to_string();
           (status, body_str)
       }

       #[tokio::test]
       async fn test_health() {
           let app = app();
           let req = Request::builder().uri("/health").body(Body::empty()).unwrap();
           let (status, body) = send_req(app, req).await;
           assert_eq!(status, 200);
           let v: serde_json::Value = serde_json::from_str(&body).unwrap();
           assert_eq!(v["status"], "ok");
       }

       #[tokio::test]
       async fn test_create_task() {
           let app = app();
           let req = Request::builder()
               .method("POST")
               .uri("/tasks")
               .header("content-type", "application/json")
               .body(Body::from(r#"{"title":"Test Task"}"#))
               .unwrap();
           let (status, body) = send_req(app, req).await;
           assert_eq!(status, 201);
           let task: Task = serde_json::from_str(&body).unwrap();
           assert_eq!(task.id, 1);
           assert_eq!(task.title, "Test Task");
           assert_eq!(task.done, false);
       }

       #[tokio::test]
       async fn test_get_task() {
           let app = app();
           // create
           let req = Request::builder()
               .method("POST")
               .uri("/tasks")
               .header("content-type", "application/json")
               .body(Body::from(r#"{"title":"Get Me"}"#))
               .unwrap();
           let (status, body) = send_req(app.clone(), req).await;
           assert_eq!(status, 201);
           let task: Task = serde_json::from_str(&body).unwrap();

           // get
           let req = Request::builder()
               .uri(format!("/tasks/{}", task.id))
               .body(Body::empty())
               .unwrap();
           let (status, body) = send_req(app, req).await;
           assert_eq!(status, 200);
           let got: Task = serde_json::from_str(&body).unwrap();
           assert_eq!(got.id, task.id);
       }

       #[tokio::test]
       async fn test_get_task_404() {
           let app = app();
           let req = Request::builder().uri("/tasks/999").body(Body::empty()).unwrap();
           let (status, _) = send_req(app, req).await;
           assert_eq!(status, 404);
       }

       #[tokio::test]
       async fn test_delete_task() {
           let app = app();
           // create
           let req = Request::builder()
               .method("POST")
               .uri("/tasks")
               .header("content-type", "application/json")
               .body(Body::from(r#"{"title":"Delete Me"}"#))
               .unwrap();
           let (status, body) = send_req(app.clone(), req).await;
           assert_eq!(status, 201);
           let task: Task = serde_json::from_str(&body).unwrap();

           // delete
           let req = Request::builder()
               .method("DELETE")
               .uri(format!("/tasks/{}", task.id))
               .body(Body::empty())
               .unwrap();
           let (status, _) = send_req(app.clone(), req).await;
           assert_eq!(status, 204);

           // verify gone
           let req = Request::builder()
               .uri(format!("/tasks/{}", task.id))
               .body(Body::empty())
               .unwrap();
           let (status, _) = send_req(app, req).await;
           assert_eq!(status, 404);
       }
   }
   ```

   Wait, `app.clone()` works because `Router` implements `Clone`. That's fine.

   Check dependencies and imports:
   - `axum` 0.8 uses `axum::Router`, `axum::Json`, `axum::Path`, `axum::State`, `axum::http::StatusCode`, `axum::body::Body`.
   - `tokio` for runtime and macros.
   - `serde` for derive.
   - `serde_json` for parsing in tests.
   - `tower` for `ServiceExt`.
   - `http_body_util` for `BodyExt`.

   One detail: `http_body_util` in 0.1 provides `BodyExt` trait. We need to import it.
   In axum 0.8, `Body` is `axum::body::Body`. `BodyExt` is in `http_body_util`.
   Actually, `http_body_util` re-exports `BodyExt`. We'll use `use http_body_util::BodyExt;`.

   Let's verify `app()` signature: `pub fn app() -> axum::Router`
   The prompt says: "Expose a function `pub fn app() -> axum::Router` that builds the router with fresh state, so that tests can call it directly."

   All good.

   Check for warnings:
   - Unused imports? We'll be careful.
   - `#[derive(serde::Serialize, serde::Deserialize)]` on `Task`.
   - `CreateTaskReq` and `UpdateTaskReq` only need `Deserialize`.
   - `AppState` doesn't need serde.
   - `RwLock` from `std::sync`.
   - `BTreeMap` from `std::collections`.
   - All handlers use `State`, `Json`, `Path`, `StatusCode`.
   - `main` uses `axum::serve`, `tokio::net::TcpListener`.

   Let's draft `main`:
   ```rust
   #[tokio::main]
   async fn main() {
       let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
       axum::serve(listener, app()).await.unwrap();
   }
   ```

   Check axum 0.8 `serve` signature: `pub async fn serve<L, S>(listener: L, into_make_service: S) -> Result<(), Infallible>` where `S` is `IntoMakeService<T>`. Passing `app()` works because `Router` implements `IntoMakeService`.

   One thing: `axum::Router` needs to be imported. We'll use `use axum::{Router, Json, Path, State, routing::{get, post, put, delete}, http::StatusCode};`

   Let's assemble the full `src/main.rs`:

   ```rust
   use axum::{
       Json, Path, State, Router,
       routing::{get, post, put, delete},
       http::StatusCode,
   };
   use serde::{Deserialize, Serialize};
   use std::collections::BTreeMap;
   use std::sync::{Arc, RwLock};

   #[derive(Serialize, Deserialize)]
   struct Task {
       id: u64,
       title: String,
       done: bool,
   }

   #[derive(Deserialize)]
   struct CreateTaskReq {
       title: String,
   }

   #[derive(Deserialize)]
   struct UpdateTaskReq {
       title: String,
       done: bool,
   }

   struct AppState {
       tasks: BTreeMap<u64, Task>,
       next_id: u64,
   }

   pub fn app() -> Router {
       let state = Arc::new(RwLock::new(AppState {
           tasks: BTreeMap::new(),
           next_id: 1,
       }));

       Router::new()
           .route("/health", get(health_handler))
           .route("/tasks", get(list_tasks).post(create_task))
           .route("/tasks/{id}", get(get_task).put(update_task).delete(delete_task))
           .with_state(state)
   }

   async fn health_handler() -> Json<serde_json::Value> {
       Json(serde_json::json!({"status": "ok"}))
   }

   async fn list_tasks(State(state): State<Arc<RwLock<AppState>>>) -> Json<Vec<Task>> {
       let guard = state.read().unwrap();
       let tasks = guard.tasks.values().cloned().collect();
       Json(tasks)
   }

   async fn create_task(
       State(state): State<Arc<RwLock<AppState>>>,
       Json(req): Json<CreateTaskReq>,
   ) -> (StatusCode, Json<Task>) {
       let mut guard = state.write().unwrap();
       let id = guard.next_id;
       guard.next_id += 1;
       let task = Task {
           id,
           title: req.title,
           done: false,
       };
       guard.tasks.insert(id, task.clone());
       (StatusCode::CREATED, Json(task))
   }

   async fn get_task(
       Path(id): Path<u64>,
       State(state): State<Arc<RwLock<AppState>>>,
   ) -> Result<Json<Task>, (StatusCode, String)> {
       let guard = state.read().unwrap();
       match guard.tasks.get(&id).cloned() {
           Some(task) => Ok(Json(task)),
           None => Err((StatusCode::NOT_FOUND, "task not found".into())),
       }
   }

   async fn update_task(
       Path(id): Path<u64>,
       State(state): State<Arc<RwLock<AppState>>>,
       Json(req): Json<UpdateTaskReq>,
   ) -> Result<Json<Task>, (StatusCode, String)> {
       let mut guard = state.write().unwrap();
       match guard.tasks.get_mut(&id) {
           Some(task) => {
               task.title = req.title;
               task.done = req.done;
               Ok(Json(task.clone()))
           }
           None => Err((StatusCode::NOT_FOUND, "task not found".into())),
       }
   }

   async fn delete_task(
       Path(id): Path<u64>,
       State(state): State<Arc<RwLock<AppState>>>,
   ) -> Result<StatusCode, (StatusCode, String)> {
       let mut guard = state.write().unwrap();
       if guard.tasks.remove(&id).is_some() {
           Ok(StatusCode::NO_CONTENT)
       } else {
           Err((StatusCode::NOT_FOUND, "task not found".into()))
       }
   }

   #[tokio::main]
   async fn main() {
       let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
       axum::serve(listener, app()).await.unwrap();
   }

   #[cfg(test)]
   mod tests {
       use super::*;
       use axum::body::Body;
       use axum::http::Request;
       use http_body_util::BodyExt;
       use tower::ServiceExt;

       async fn send_req(app: Router, req: Request<Body>) -> (StatusCode, String) {
           let resp = app.oneshot(req).await.unwrap();
           let status = resp.status();
           let body_bytes = resp.into_body().collect().await.unwrap().to_bytes();
           let body_str = String::from_utf8_lossy(&body_bytes).to_string();
           (status, body_str)
       }

       #[tokio::test]
       async fn test_health() {
           let app = app();
           let req = Request::builder().uri("/health").body(Body::empty()).unwrap();
           let (status, body) = send_req(app, req).await;
           assert_eq!(status, 200);
           let v: serde_json::Value = serde_json::from_str(&body).unwrap();
           assert_eq!(v["status"], "ok");
       }

       #[tokio::test]
       async fn test_post_task_returns_201_and_id_1() {
           let app = app();
           let req = Request::builder()
               .method("POST")
               .uri("/tasks")
               .header("content-type", "application/json")
               .body(Body::from(r#"{"title":"First Task"}"#))
               .unwrap();
           let (status, body) = send_req(app, req).await;
           assert_eq!(status, 201);
           let task: Task = serde_json::from_str(&body).unwrap();
           assert_eq!(task.id, 1);
       }

       #[tokio::test]
       async fn test_get_task_after_creation() {
           let app = app();
           let req = Request::builder()
               .method("POST")
               .uri("/tasks")
               .header("content-type", "application/json")
               .body(Body::from(r#"{"title":"Get Me"}"#))
               .unwrap();
           let (status, body) = send_req(app.clone(), req).await;
           assert_eq!(status, 201);
           let task: Task = serde_json::from_str(&body).unwrap();

           let req = Request::builder()
               .uri(format!("/tasks/{}", task.id))
               .body(Body::empty())
               .unwrap();
           let (status, body) = send_req(app, req).await;
           assert_eq!(status, 200);
           let got: Task = serde_json::from_str(&body).unwrap();
           assert_eq!(got.id, task.id);
           assert_eq!(got.title, task.title);
       }

       #[tokio::test]
       async fn test_get_nonexistent_task_returns_404() {
           let app = app();
           let req = Request::builder().uri("/tasks/999").body(Body::empty()).unwrap();
           let (status, _) = send_req(app, req).await;
           assert_eq!(status, 404);
       }

       #[tokio::test]
       async fn test_delete_task_returns_204_and_subsequent_get_returns_404() {
           let app = app();
           let req = Request::builder()
               .method("POST")
               .uri("/tasks")
               .header("content-type", "application/json")
               .body(Body::from(r#"{"title":"Delete Me"}"#))
               .unwrap();
           let (status, body) = send_req(app.clone(), req).await;
           assert_eq!(status, 201);
           let task: Task = serde_json::from_str(&body).unwrap();

           let req = Request::builder()
               .method("DELETE")
               .uri(format!("/tasks/{}", task.id))
               .body(Body::empty())
               .unwrap();
           let (status, _) = send_req(app.clone(), req).await;
           assert_eq!(status, 204);

           let req = Request::builder()
               .uri(format!("/tasks/{}", task.id))
               .body(Body::empty())
               .unwrap();
           let (status, _) = send_req(app, req).await;
           assert_eq!(status, 404);
       }
   }
   ```

   Check constraints:
   - Crate name: `task_api` -> in Cargo.toml
   - Dependencies exact versions -> checked
   - Data model -> matches
   - Endpoints -> all implemented
   - In-memory state with `Arc<RwLock<...>>` -> used
   - `pub fn app() -> axum::Router` -> implemented
   - `main` binds to `0.0.0.0:3000` -> implemented
   - No warnings -> should be fine. I'll double check imports and usage.
   - Tests use `tower::ServiceExt::oneshot` -> yes
   - Tests cover required cases -> yes
   - Output format -> strict

   One minor thing: `axum::Router` vs `Router`. I'll use `axum::Router` in the signature to be explicit, or just import `Router`. The prompt says `pub fn app() -> axum::Router`. I'll stick to that or import it. I'll import `Router` and use it, but the signature can be `pub fn app() -> Router`. It's fine.

   Check `http_body_util` usage: `BodyExt` is correct. `collect()` returns `Collected<Body>` which has `to_bytes()`. This is standard in 0.1.

   Check `serde_json` usage in tests: `serde_json::from_str` is fine.

   Check `RwLock` from `std::sync`: `unwrap()` on lock is fine for this simple API. In production we'd handle poisoning, but for this constraint it's acceptable. To avoid any "unwrap" warnings or pedantic issues, I'll keep it. It's standard.

   One detail: `axum` 0.8 might require `#[tokio::main]` for `main`. I have it.
   Another: `axum::serve` is correct.
   Another: `Router::new().route(...).with_state(...)` is correct for axum 0.8.

   Let's verify `Cargo.toml`:
   ```toml
   [package]
   name = "task_api"
   version = "0.1.0"
   edition = "2021"

   [dependencies]
   axum = "0.8"
   tokio = { version = "1", features = ["full"] }
   serde = { version = "1", features = ["derive"] }
   serde_json = "1"

   [dev-dependencies]
   tower = { version = "0.5", features = ["util"] }
   http-body-util = "0.1"
   ```

   All matches requirements exactly.

   One thing: `axum` 0.8 uses `http` 1.x. `tower` 0.5 is compatible. `http-body-util` 0.1 is compatible.
   The code should compile cleanly.

   Let's quickly mentally run through potential warnings:
   - `use serde::{Deserialize, Serialize};` -> used on Task.
   - `use std::collections::BTreeMap;` -> used.
   - `use std::sync::{Arc, RwLock};` -> used.
   - `use axum::{...}` -> used.
   - `#[derive(Serialize, Deserialize)]` on Task -> used.
   - `CreateTaskReq`, `UpdateTaskReq` -> used.
   - `AppState` -> used.
   - `app()` -> used.
   - handlers -> used.
   - `main` -> used.
   - tests -> use `super::*`, `axum::body::Body`, `axum::http::Request`, `http_body_util::BodyExt`, `tower::ServiceExt`.
   - All good.

   One minor adjustment: `axum::Router` in the signature vs import. I'll just import `Router` and use it. The prompt says `pub fn app() -> axum::Router`, I'll write exactly that to be safe, or import it. I'll stick to `pub fn app() -> Router` but add `use axum::Router;`. It's fine.

   I will produce the output exactly as requested.
   Structure:
   ### FILE: Cargo.toml
   ### FILE: src/main.rs
   No extra text.
   Code blocks fenced.
   Ready. 
   Self-Correction/Verification during thought:
   - `http_body_util::BodyExt` might need to be imported as `use http_body_util::BodyExt;`. Yes.
   - `tower::ServiceExt` is correct.
   - `app.oneshot(req)` requires `app` to be `Router`. `Router` implements `Clone`, so `app.clone()` is fine.
   - `StatusCode` in tests: I used `super::*` which includes `StatusCode`. That's fine.
   - `Body::empty()` and `Body::from()` are from `axum::body::Body`. Correct.
   - `Request::builder()` from `axum::http::Request`. Correct.
   - All matches.

   One thing: `axum` 0.8 might have changed `serve` signature slightly? No, it's stable. `axum::serve(listener, app()).await.unwrap();` is standard.
   I'll output exactly.
   Proceed. 
   Output matches response. 
   All constraints met. 
   Generating. 
   [Done] 
   Output matches the final code. 
   (Self-Correction/Note during generation prep)
   I should ensure `serde_json` is available in test scope. It's a dependency, so it's available.
   I'll make sure `use serde_json;` is either global or in test. It's global.
   All good. 
   Output matches. 
   Proceeds. 
   [Final Check of the Prompt]
   "Expose a function `pub fn app() -> axum::Router`" -> I will write exactly that.
   "Use exactly these versions" -> Checked.
   "In-memory state only. Use `Arc<RwLock<...>>`" -> Checked.
   "tests in `src/main.rs`" -> Checked.
   "drive the router via `tower::ServiceExt::oneshot`" -> Checked.
   "no commentary" -> Checked.
   Ready. 
   Output generation. 
   [Output Generation] -> *Proceeds*
