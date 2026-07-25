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
    response::Json,
    routing::{get, post, put, delete},
    Router,
};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock};
use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Serialize, Deserialize, Clone, Debug)]
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
    title: Option<String>,
    done: Option<bool>,
}

struct AppState {
    tasks: RwLock<Vec<Task>>,
    next_id: AtomicU64,
}

fn app_with_state(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/tasks", get(list_tasks))
        .route("/tasks", post(create_task))
        .route("/tasks/{id}", get(get_task))
        .route("/tasks/{id}", put(update_task))
        .route("/tasks/{id}", delete(delete_task))
        .with_state(state)
}

pub fn app() -> Router {
    let state = Arc::new(AppState {
        tasks: RwLock::new(Vec::new()),
        next_id: AtomicU64::new(1),
    });
    app_with_state(state)
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
}

async fn list_tasks(state: axum::extract::State<Arc<AppState>>) -> Json<Vec<Task>> {
    let tasks = state.tasks.read().unwrap();
    Json(tasks.clone())
}

async fn create_task(
    state: axum::extract::State<Arc<AppState>>,
    Json(req): Json<CreateTaskRequest>,
) -> (StatusCode, Json<Task>) {
    let id = state.next_id.fetch_add(1, Ordering::SeqCst);
    let task = Task {
        id,
        title: req.title,
        done: false,
    };

    let mut tasks = state.tasks.write().unwrap();
    tasks.push(task.clone());

    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    Path(id): Path<u64>,
    state: axum::extract::State<Arc<AppState>>,
) -> Result<Json<Task>, StatusCode> {
    let tasks = state.tasks.read().unwrap();
    tasks
        .iter()
        .find(|t| t.id == id)
        .cloned()
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}

async fn update_task(
    Path(id): Path<u64>,
    state: axum::extract::State<Arc<AppState>>,
    Json(req): Json<UpdateTaskRequest>,
) -> Result<Json<Task>, StatusCode> {
    let mut tasks = state.tasks.write().unwrap();
    if let Some(task) = tasks.iter_mut().find(|t| t.id == id) {
        if let Some(title) = req.title {
            task.title = title;
        }
        if let Some(done) = req.done {
            task.done = done;
        }
        Ok(Json(task.clone()))
    } else {
        Err(StatusCode::NOT_FOUND)
    }
}

async fn delete_task(
    Path(id): Path<u64>,
    state: axum::extract::State<Arc<AppState>>,
) -> Result<StatusCode, StatusCode> {
    let mut tasks = state.tasks.write().unwrap();
    let initial_len = tasks.len();
    tasks.retain(|t| t.id != id);

    if tasks.len() < initial_len {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(StatusCode::NOT_FOUND)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    #[tokio::test]
    async fn test_health() {
        let app = app();
        let response = app
            .clone()
            .oneshot(Request::builder().uri("/health").body(Body::empty()).unwrap())
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(std::str::from_utf8(&body).unwrap(), r#"{"status":"ok"}"#);
    }

    #[tokio::test]
    async fn test_create_task() {
        let app = app();
        let request_body = serde_json::json!({"title": "Test Task"});
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/tasks")
                    .header("content-type", "application/json")
                    .body(Body::from(request_body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::CREATED);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let task: Task = serde_json::from_slice(&body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Test Task");
        assert!(!task.done);
    }

    #[tokio::test]
    async fn test_get_task_after_creation() {
        let app = app();
        let request_body = serde_json::json!({"title": "Test Task"});
        let _ = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/tasks")
                    .header("content-type", "application/json")
                    .body(Body::from(request_body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();

        let response = app
            .clone()
            .oneshot(Request::builder().uri("/tasks/1").body(Body::empty()).unwrap())
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let task: Task = serde_json::from_slice(&body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Test Task");
    }

    #[tokio::test]
    async fn test_get_nonexistent_task() {
        let app = app();
        let response = app
            .clone()
            .oneshot(Request::builder().uri("/tasks/999").body(Body::empty()).unwrap())
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task() {
        let app = app();

        // Create a task first
        let request_body = serde_json::json!({"title": "Task to delete"});
        let _ = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/tasks")
                    .header("content-type", "application/json")
                    .body(Body::from(request_body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();

        // Delete the task
        let response = app
            .clone()
            .oneshot(Request::builder().method("DELETE").uri("/tasks/1").body(Body::empty()).unwrap())
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::NO_CONTENT);

        // Verify it's gone
        let response = app
            .clone()
            .oneshot(Request::builder().uri("/tasks/1").body(Body::empty()).unwrap())
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
}

use axum::extract::State;
use axum::Json as AxumJson;
use http::Request;
```