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
    body::Body,
    extract::{Path, State},
    http::{Request, StatusCode},
    Json,
    Router,
    routing::{delete, get, post, put},
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Serialize, Deserialize, Clone)]
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
    tasks: HashMap<u64, Task>,
    next_id: u64,
}

pub fn app() -> axum::Router {
    let state = Arc::new(RwLock::new(AppState {
        tasks: HashMap::new(),
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
    let guard = state.read().await;
    let mut tasks: Vec<Task> = guard.tasks.values().cloned().collect();
    tasks.sort_by_key(|t| t.id);
    Json(tasks)
}

async fn create_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Json(payload): Json<CreateTaskRequest>,
) -> (StatusCode, Json<Task>) {
    let mut guard = state.write().await;
    let id = guard.next_id;
    guard.next_id += 1;
    let task = Task {
        id,
        title: payload.title,
        done: false,
    };
    guard.tasks.insert(id, task.clone());
    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    Path(id): Path<u64>,
    State(state): State<Arc<RwLock<AppState>>>,
) -> Result<Json<Task>, StatusCode> {
    let guard = state.read().await;
    match guard.tasks.get(&id) {
        Some(task) => Ok(Json(task.clone())),
        None => Err(StatusCode::NOT_FOUND),
    }
}

async fn update_task(
    Path(id): Path<u64>,
    State(state): State<Arc<RwLock<AppState>>>,
    Json(payload): Json<UpdateTaskRequest>,
) -> Result<Json<Task>, StatusCode> {
    let mut guard = state.write().await;
    match guard.tasks.get_mut(&id) {
        Some(task) => {
            task.title = payload.title;
            task.done = payload.done;
            Ok(Json(task.clone()))
        }
        None => Err(StatusCode::NOT_FOUND),
    }
}

async fn delete_task(
    Path(id): Path<u64>,
    State(state): State<Arc<RwLock<AppState>>>,
) -> Result<StatusCode, StatusCode> {
    let mut guard = state.write().await;
    if guard.tasks.remove(&id).is_some() {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(StatusCode::NOT_FOUND)
    }
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
    use axum::body::Body;
    use axum::http::Request;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    #[tokio::test]
    async fn test_health() {
        let app = app();
        let req = Request::builder()
            .method("GET")
            .uri("/health")
            .body(Body::empty())
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn test_create_task_returns_201_and_id_1() {
        let app = app();
        let payload = serde_json::json!({"title": "Test"}).to_string();
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(payload))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::CREATED);
        let bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let task: Task = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(task.id, 1);
    }

    #[tokio::test]
    async fn test_get_task_after_creation() {
        let app = app();
        let create_req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::json!({"title": "GetMe"}).to_string()))
            .unwrap();
        app.clone().oneshot(create_req).await.unwrap();

        let get_req = Request::builder()
            .method("GET")
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let resp = app.oneshot(get_req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let task: Task = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(task.title, "GetMe");
    }

    #[tokio::test]
    async fn test_get_nonexistent_task_returns_404() {
        let app = app();
        let req = Request::builder()
            .method("GET")
            .uri("/tasks/999")
            .body(Body::empty())
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task_and_verify_404() {
        let app = app();
        let create_req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::json!({"title": "DeleteMe"}).to_string()))
            .unwrap();
        app.clone().oneshot(create_req).await.unwrap();

        let delete_req = Request::builder()
            .method("DELETE")
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(delete_req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::NO_CONTENT);

        let get_req = Request::builder()
            .method("GET")
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let resp = app.oneshot(get_req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }
}
```