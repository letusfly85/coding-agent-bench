### FILE: Cargo.toml
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

### FILE: src/main.rs
```rust
use axum::{
    extract::Path,
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post, put, delete},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock};

#[derive(Clone, Serialize, Deserialize)]
struct Task {
    id: u64,
    title: String,
    done: bool,
}

#[derive(Deserialize)]
struct CreateTaskRequest {
    title: String,
}

#[derive(Deserialize)]
struct UpdateTaskRequest {
    title: String,
    done: bool,
}

struct AppState {
    tasks: Vec<Task>,
    next_id: u64,
}

impl AppState {
    fn new() -> Self {
        Self {
            tasks: Vec::new(),
            next_id: 1,
        }
    }
}

async fn health() -> Json<{ json: &str }> {
    Json(serde_json::json!({"status": "ok"}))
}

async fn list_tasks(State(state): State<Arc<RwLock<AppState>>>) -> Json<Vec<Task>> {
    let state = state.read().unwrap();
    Json(state.tasks.clone())
}

async fn create_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Json(payload): Json<CreateTaskRequest>,
) -> (StatusCode, Json<Task>) {
    let mut state = state.write().unwrap();
    let task = Task {
        id: state.next_id,
        title: payload.title,
        done: false,
    };
    state.next_id += 1;
    state.tasks.push(task.clone());
    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    Path(id): Path<u64>,
    State(state): State<Arc<RwLock<AppState>>>,
) -> Json<Option<Task>> {
    let state = state.read().unwrap();
    Json(state.tasks.iter().find(|t| t.id == id).cloned())
}

async fn update_task(
    Path(id): Path<u64>,
    State(state): State<Arc<RwLock<AppState>>>,
    Json(payload): Json<UpdateTaskRequest>,
) -> Json<Task> {
    let mut state = state.write().unwrap();
    if let Some(task) = state.tasks.iter_mut().find(|t| t.id == id) {
        task.title = payload.title;
        task.done = payload.done;
        Json(task.clone())
    } else {
        Json(Task {
            id,
            title: String::new(),
            done: false,
        })
    }
}

async fn delete_task(
    Path(id): Path<u64>,
    State(state): State<Arc<RwLock<AppState>>>,
) -> StatusCode {
    let mut state = state.write().unwrap();
    if let Some(pos) = state.tasks.iter().position(|t| t.id == id) {
        state.tasks.remove(pos);
        StatusCode::NO_CONTENT
    } else {
        StatusCode::NOT_FOUND
    }
}

// Wrapper for axum State extractor
use axum::extract::State;

pub fn app() -> Router {
    let state = Arc::new(RwLock::new(AppState::new()));
    Router::new()
        .route("/health", get(health))
        .route("/tasks", get(list_tasks))
        .route("/tasks", post(create_task))
        .route("/tasks/{id}", get(get_task))
        .route("/tasks/{id}", put(update_task))
        .route("/tasks/{id}", delete(delete_task))
        .with_state(state)
}

#[tokio::main]
async fn main() {
    let app = app();
    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;
    use http_body_util::Full;
    use hyper::body::Bytes;
    use tower::ServiceExt;

    #[tokio::test]
    async fn health_check() {
        let app = app();
        let request = hyper::Request::builder()
            .uri("/health")
            .body(Full::default())
            .unwrap();
        let response = app.oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn create_task() {
        let app = app();
        let body = serde_json::to_vec(&serde_json::json!({"title": "Test task"})).unwrap();
        let request = hyper::Request::builder()
            .uri("/tasks")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Full::from(Bytes::from(body)))
            .unwrap();
        let response = app.oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        let body = hyper::body::to_bytes(response.into_body()).await.unwrap();
        let task: Task = serde_json::from_slice(&body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Test task");
        assert!(!task.done);
    }

    #[tokio::test]
    async fn get_created_task() {
        let app = app();
        // First create a task
        let body = serde_json::to_vec(&serde_json::json!({"title": "Task A"})).unwrap();
        let request = hyper::Request::builder()
            .uri("/tasks")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Full::from(Bytes::from(body)))
            .unwrap();
        let _ = app.oneshot(request).await.unwrap();

        // Now get it
        let request = hyper::Request::builder()
            .uri("/tasks/1")
            .body(Full::default())
            .unwrap();
        let response = app.oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = hyper::body::to_bytes(response.into_body()).await.unwrap();
        let task: Task = serde_json::from_slice(&body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Task A");
    }

    #[tokio::test]
    async fn get_nonexistent_task() {
        let app = app();
        let request = hyper::Request::builder()
            .uri("/tasks/999")
            .body(Full::default())
            .unwrap();
        let response = app.oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn delete_task() {
        let app = app();
        // Create a task first
        let body = serde_json::to_vec(&serde_json::json!({"title": "Task B"})).unwrap();
        let request = hyper::Request::builder()
            .uri("/tasks")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Full::from(Bytes::from(body)))
            .unwrap();
        let _ = app.oneshot(request).await.unwrap();

        // Delete the task
        let request = hyper::Request::builder()
            .uri("/tasks/1")
            .method("DELETE")
            .body(Full::default())
            .unwrap();
        let response = app.oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::NO_CONTENT);

        // Verify deletion
        let request = hyper::Request::builder()
            .uri("/tasks/1")
            .body(Full::default())
            .unwrap();
        let response = app.oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
}
```