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
    Json, Router,
    routing::{delete, get, post, put},
    extract::{Path, State},
    http::StatusCode,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

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
    title: String,
    done: bool,
}

struct AppState {
    tasks: HashMap<u64, Task>,
    next_id: u64,
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({ "status": "ok" }))
}

async fn list_tasks(state: State<Arc<RwLock<AppState>>>) -> Json<Vec<Task>> {
    let mut tasks = state.read().await.tasks.values().cloned().collect::<Vec<_>>();
    tasks.sort_by_key(|t| t.id);
    Json(tasks)
}

async fn create_task(
    state: State<Arc<RwLock<AppState>>>,
    Json(payload): Json<CreateTaskRequest>,
) -> (StatusCode, Json<Task>) {
    let mut app_state = state.write().await;
    let id = app_state.next_id;
    app_state.next_id += 1;
    let task = Task {
        id,
        title: payload.title,
        done: false,
    };
    app_state.tasks.insert(id, task.clone());
    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    state: State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> Result<Json<Task>, StatusCode> {
    let app_state = state.read().await;
    match app_state.tasks.get(&id) {
        Some(task) => Ok(Json(task.clone())),
        None => Err(StatusCode::NOT_FOUND),
    }
}

async fn update_task(
    state: State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
    Json(payload): Json<UpdateTaskRequest>,
) -> Result<Json<Task>, StatusCode> {
    let mut app_state = state.write().await;
    if let Some(task) = app_state.tasks.get_mut(&id) {
        task.title = payload.title;
        task.done = payload.done;
        Ok(Json(task.clone()))
    } else {
        Err(StatusCode::NOT_FOUND)
    }
}

async fn delete_task(
    state: State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> Result<StatusCode, StatusCode> {
    let mut app_state = state.write().await;
    if app_state.tasks.remove(&id).is_some() {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(StatusCode::NOT_FOUND)
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
    use axum::http::{Request, StatusCode};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    async fn get_body(res: axum::http::Response<Body>) -> String {
        let (_, body) = res.into_parts();
        let bytes = body.collect().await.unwrap().to_bytes();
        String::from_utf8(bytes.to_vec()).unwrap()
    }

    #[tokio::test]
    async fn test_health() {
        let mut app = app();
        let req = Request::builder()
            .uri("/health")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = get_body(res).await;
        assert_eq!(body, r#"{"status":"ok"}"#);
    }

    #[tokio::test]
    async fn test_create_task() {
        let mut app = app();
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(r#"{"title":"Test"}"#))
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::CREATED);
        let body = get_body(res).await;
        let task: Task = serde_json::from_str(&body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Test");
        assert_eq!(task.done, false);
    }

    #[tokio::test]
    async fn test_get_task_after_creation() {
        let mut app = app();
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(r#"{"title":"Test"}"#))
            .unwrap();
        app.oneshot(req).await.unwrap();

        let req = Request::builder()
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = get_body(res).await;
        let task: Task = serde_json::from_str(&body).unwrap();
        assert_eq!(task.id, 1);
    }

    #[tokio::test]
    async fn test_get_nonexistent_task() {
        let mut app = app();
        let req = Request::builder()
            .uri("/tasks/999")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task() {
        let mut app = app();
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(r#"{"title":"To delete"}"#))
            .unwrap();
        app.oneshot(req).await.unwrap();

        let req = Request::builder()
            .method("DELETE")
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::NO_CONTENT);

        let req = Request::builder()
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::NOT_FOUND);
    }
}
```