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
use std::{
    sync::{Arc, RwLock},
    sync::atomic::{AtomicU64, Ordering},
};

#[derive(Serialize, Deserialize, Clone, Debug)]
struct Task {
    id: u64,
    title: String,
    done: bool,
}

struct AppState {
    tasks: RwLock<Vec<Task>>,
    next_id: AtomicU64,
}

impl AppState {
    fn new() -> Self {
        AppState {
            tasks: RwLock::new(Vec::new()),
            next_id: AtomicU64::new(1),
        }
    }
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

async fn health() -> impl IntoResponse {
    Json(serde_json::json!({"status": "ok"}))
}

async fn list_tasks(state: axum::extract::State<Arc<AppState>>) -> impl IntoResponse {
    let tasks = state.tasks.read().unwrap();
    Json(tasks.clone())
}

async fn create_task(
    state: axum::extract::State<Arc<AppState>>,
    Json(payload): Json<CreateTaskRequest>,
) -> impl IntoResponse {
    let id = state.next_id.fetch_add(1, Ordering::SeqCst);
    let task = Task {
        id,
        title: payload.title,
        done: false,
    };

    let mut tasks = state.tasks.write().unwrap();
    tasks.push(task.clone());

    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    state: axum::extract::State<Arc<AppState>>,
    Path(id): Path<u64>,
) -> impl IntoResponse {
    let tasks = state.tasks.read().unwrap();
    if let Some(task) = tasks.iter().find(|t| t.id == id) {
        (StatusCode::OK, Json(task.clone()))
    } else {
        StatusCode::NOT_FOUND
    }
}

async fn update_task(
    state: axum::extract::State<Arc<AppState>>,
    Path(id): Path<u64>,
    Json(payload): Json<UpdateTaskRequest>,
) -> impl IntoResponse {
    let mut tasks = state.tasks.write().unwrap();
    if let Some(task) = tasks.iter_mut().find(|t| t.id == id) {
        task.title = payload.title;
        task.done = payload.done;
        (StatusCode::OK, Json(task.clone()))
    } else {
        StatusCode::NOT_FOUND
    }
}

async fn delete_task(
    state: axum::extract::State<Arc<AppState>>,
    Path(id): Path<u64>,
) -> impl IntoResponse {
    let mut tasks = state.tasks.write().unwrap();
    if let Some(pos) = tasks.iter().position(|t| t.id == id) {
        tasks.remove(pos);
        StatusCode::NO_CONTENT
    } else {
        StatusCode::NOT_FOUND
    }
}

pub fn app() -> Router {
    let state = Arc::new(AppState::new());
    Router::new()
        .route("/health", get(health))
        .route("/tasks", post(create_task))
        .route("/tasks", get(list_tasks))
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
    use hyper::{Body, Request};
    use tower::ServiceExt;

    async fn call_service(app: &Router, req: Request<Body>) -> hyper::Response<Full<bytes::Bytes>> {
        app.clone()
            .oneshot(req)
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn health_check_returns_200() {
        let app = app();
        let req = Request::builder().uri("/health").body(Body::empty()).unwrap();
        let res = call_service(&app, req).await;

        assert_eq!(res.status(), StatusCode::OK);
        let body = hyper::body::to_bytes(res.into_body()).await.unwrap();
        assert_eq!(&*body, br#"{"status":"ok"}"#);
    }

    #[tokio::test]
    async fn post_tasks_creates_new_task_with_id_1() {
        let app = app();
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("content-type", "application/json")
            .body(Body::from(r#"{"title":"Test Task"}"#))
            .unwrap();
        let res = call_service(&app, req).await;

        assert_eq!(res.status(), StatusCode::CREATED);
        let body = hyper::body::to_bytes(res.into_body()).await.unwrap();
        let task: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(task["id"], 1);
        assert_eq!(task["title"], "Test Task");
        assert_eq!(task["done"], false);
    }

    #[tokio::test]
    async fn get_existing_task_returns_200() {
        let app = app();
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("content-type", "application/json")
            .body(Body::from(r#"{"title":"Task 1"}"#))
            .unwrap();
        let _ = call_service(&app, req).await;

        let req = Request::builder()
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let res = call_service(&app, req).await;

        assert_eq!(res.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn get_nonexistent_task_returns_404() {
        let app = app();
        let req = Request::builder()
            .uri("/tasks/999")
            .body(Body::empty())
            .unwrap();
        let res = call_service(&app, req).await;

        assert_eq!(res.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn delete_task_and_get_returns_404() {
        let app = app();
        
        // Create a task first
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("content-type", "application/json")
            .body(Body::from(r#"{"title":"Task to delete"}"#))
            .unwrap();
        let _ = call_service(&app, req).await;

        // Delete the task
        let req = Request::builder()
            .method("DELETE")
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let res = call_service(&app, req).await;
        assert_eq!(res.status(), StatusCode::NO_CONTENT);

        // Verify task is gone
        let req = Request::builder()
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let res = call_service(&app, req).await;
        assert_eq!(res.status(), StatusCode::NOT_FOUND);
    }
}
```