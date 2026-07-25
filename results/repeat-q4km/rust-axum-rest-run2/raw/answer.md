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
    http::StatusCode,
    routing::{delete, get, post, put},
    Router, Json,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Task {
    id: u64,
    title: String,
    done: bool,
}

#[derive(Debug, Deserialize)]
struct CreateTaskRequest {
    title: String,
}

#[derive(Debug, Deserialize)]
struct UpdateTaskRequest {
    title: String,
    done: bool,
}

struct AppState {
    tasks: HashMap<u64, Task>,
    next_id: u64,
}

impl AppState {
    fn new() -> Self {
        Self {
            tasks: HashMap::new(),
            next_id: 1,
        }
    }
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
}

async fn list_tasks(State(state): State<Arc<RwLock<AppState>>>) -> Json<Vec<Task>> {
    let mut tasks = state.read().await.tasks.values().cloned().collect::<Vec<_>>();
    tasks.sort_by_key(|t| t.id);
    Json(tasks)
}

async fn create_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Json(req): Json<CreateTaskRequest>,
) -> (StatusCode, Json<Task>) {
    let mut app_state = state.write().await;
    let id = app_state.next_id;
    app_state.next_id += 1;
    let task = Task {
        id,
        title: req.title,
        done: false,
    };
    app_state.tasks.insert(id, task.clone());
    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> Result<Json<Task>, StatusCode> {
    let app_state = state.read().await;
    match app_state.tasks.get(&id).cloned() {
        Some(task) => Ok(Json(task)),
        None => Err(StatusCode::NOT_FOUND),
    }
}

async fn update_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
    Json(req): Json<UpdateTaskRequest>,
) -> Result<Json<Task>, StatusCode> {
    let mut app_state = state.write().await;
    match app_state.tasks.get_mut(&id) {
        Some(task) => {
            task.title = req.title;
            task.done = req.done;
            Ok(Json(task.clone()))
        }
        None => Err(StatusCode::NOT_FOUND),
    }
}

async fn delete_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> StatusCode {
    let mut app_state = state.write().await;
    if app_state.tasks.remove(&id).is_some() {
        StatusCode::NO_CONTENT
    } else {
        StatusCode::NOT_FOUND
    }
}

pub fn app() -> Router {
    let state = Arc::new(RwLock::new(AppState::new()));
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
    use axum::http::{Method, Request};
    use http_body_util::BodyExt;
    use serde_json::Value;
    use tower::ServiceExt;

    async fn send_req(
        app: &Router,
        method: Method,
        uri: &str,
        body: Option<&str>,
    ) -> (StatusCode, Value) {
        let mut req = Request::builder().method(method).uri(uri);
        if body.is_some() {
            req = req.header("content-type", "application/json");
        }
        let req = match body {
            Some(b) => req.body(b.to_string()).unwrap(),
            None => req.body("").unwrap(),
        };

        let (status, body_bytes) = app.clone().oneshot(req).await.unwrap().into_parts();
        let body_str =
            String::from_utf8(body_bytes.collect().await.unwrap().to_vec()).unwrap();
        let body_val = if body_str.is_empty() {
            Value::Null
        } else {
            serde_json::from_str(&body_str).unwrap()
        };
        (status, body_val)
    }

    #[tokio::test]
    async fn test_health() {
        let app = app();
        let (status, body) = send_req(&app, Method::GET, "/health", None).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["status"], "ok");
    }

    #[tokio::test]
    async fn test_create_task() {
        let app = app();
        let (status, body) =
            send_req(&app, Method::POST, "/tasks", Some(r#"{"title":"Test"}"#)).await;
        assert_eq!(status, StatusCode::CREATED);
        assert_eq!(body["id"], 1);
    }

    #[tokio::test]
    async fn test_get_task_after_create() {
        let app = app();
        send_req(
            &app,
            Method::POST,
            "/tasks",
            Some(r#"{"title":"Task1"}"#),
        )
        .await;
        let (status, body) = send_req(&app, Method::GET, "/tasks/1", None).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["id"], 1);
        assert_eq!(body["title"], "Task1");
    }

    #[tokio::test]
    async fn test_get_nonexistent_task() {
        let app = app();
        let (status, _) = send_req(&app, Method::GET, "/tasks/999", None).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task() {
        let app = app();
        send_req(
            &app,
            Method::POST,
            "/tasks",
            Some(r#"{"title":"Del"}"#),
        )
        .await;
        let (status, _) = send_req(&app, Method::DELETE, "/tasks/1", None).await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _) = send_req(&app, Method::GET, "/tasks/1", None).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }
}
```