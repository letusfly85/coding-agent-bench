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
    extract::{Path, State},
    http::{Method, Request, StatusCode},
    routing::{delete, get, post, put},
    Json, Router,
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

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
}

async fn list_tasks(State(state): State<Arc<RwLock<AppState>>>) -> Json<Vec<Task>> {
    let guard = state.read().await;
    let mut tasks = guard.tasks.values().cloned().collect::<Vec<_>>();
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
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> Result<Json<Task>, StatusCode> {
    let guard = state.read().await;
    match guard.tasks.get(&id).cloned() {
        Some(task) => Ok(Json(task)),
        None => Err(StatusCode::NOT_FOUND),
    }
}

async fn update_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
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
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> Result<StatusCode, StatusCode> {
    let mut guard = state.write().await;
    match guard.tasks.remove(&id) {
        Some(_) => Ok(StatusCode::NO_CONTENT),
        None => Err(StatusCode::NOT_FOUND),
    }
}

pub fn app() -> Router {
    let state = Arc::new(RwLock::new(AppState {
        tasks: HashMap::new(),
        next_id: 1,
    }));

    Router::new()
        .route("/health", get(health))
        .route("/tasks", get(list_tasks).post(create_task))
        .route("/tasks/{id}", get(get_task).put(update_task).delete(delete_task))
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
    use axum::body::Body;
    use axum::http::{Request, Method, StatusCode};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    async fn send_request(
        app: Router,
        method: Method,
        uri: &str,
        body: Option<&str>,
    ) -> (StatusCode, String) {
        let mut builder = Request::builder()
            .method(method)
            .uri(uri);
        if let Some(body) = body {
            builder = builder.header("content-type", "application/json");
        }
        let req = builder
            .body(Body::from(body.unwrap_or("")))
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        let status = resp.status();
        let bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let text = String::from_utf8(bytes.to_vec()).unwrap();
        (status, text)
    }

    #[tokio::test]
    async fn test_health() {
        let app = app();
        let (status, body) = send_request(app, Method::GET, "/health", None).await;
        assert_eq!(status, StatusCode::OK);
        let val: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(val["status"], "ok");
    }

    #[tokio::test]
    async fn test_create_task() {
        let app = app();
        let (status, body) = send_request(
            app,
            Method::POST,
            "/tasks",
            Some(r#"{"title":"Test task"}"#),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let task: Task = serde_json::from_str(&body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Test task");
        assert_eq!(task.done, false);
    }

    #[tokio::test]
    async fn test_get_task() {
        let base = app();
        send_request(base.clone(), Method::POST, "/tasks", Some(r#"{"title":"Task 1"}"#)).await;
        let (status, body) = send_request(base, Method::GET, "/tasks/1", None).await;
        assert_eq!(status, StatusCode::OK);
        let task: Task = serde_json::from_str(&body).unwrap();
        assert_eq!(task.id, 1);
    }

    #[tokio::test]
    async fn test_get_task_not_found() {
        let app = app();
        let (status, _) = send_request(app, Method::GET, "/tasks/999", None).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task() {
        let base = app();
        send_request(base.clone(), Method::POST, "/tasks", Some(r#"{"title":"To delete"}"#)).await;
        let (status, _) = send_request(base.clone(), Method::DELETE, "/tasks/1", None).await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _) = send_request(base, Method::GET, "/tasks/1", None).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }
}
```