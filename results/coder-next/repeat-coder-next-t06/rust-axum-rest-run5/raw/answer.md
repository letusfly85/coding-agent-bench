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
use std::{sync::{Arc, RwLock}, sync::atomic::{AtomicU64, Ordering}};

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
    tasks: Arc<RwLock<Vec<Task>>>,
    next_id: Arc<AtomicU64>,
}

fn app_state() -> AppState {
    AppState {
        tasks: Arc::new(RwLock::new(Vec::new())),
        next_id: Arc::new(AtomicU64::new(1)),
    }
}

async fn health() -> impl IntoResponse {
    Json(serde_json::json!({"status": "ok"}))
}

async fn list_tasks(State(state): State<AppState>) -> impl IntoResponse {
    let tasks = state.tasks.read().unwrap();
    Json(tasks.clone())
}

async fn create_task(
    State(state): State<AppState>,
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
    Path(id): Path<u64>,
    State(state): State<AppState>,
) -> impl IntoResponse {
    let tasks = state.tasks.read().unwrap();
    tasks
        .iter()
        .find(|t| t.id == id)
        .cloned()
        .map(Json)
        .unwrap_or(StatusCode::NOT_FOUND)
}

async fn update_task(
    Path(id): Path<u64>,
    State(state): State<AppState>,
    Json(payload): Json<UpdateTaskRequest>,
) -> impl IntoResponse {
    let mut tasks = state.tasks.write().unwrap();
    if let Some(task) = tasks.iter_mut().find(|t| t.id == id) {
        task.title = payload.title;
        task.done = payload.done;
        return (StatusCode::OK, Json(task.clone()));
    }
    StatusCode::NOT_FOUND
}

async fn delete_task(
    Path(id): Path<u64>,
    State(state): State<AppState>,
) -> impl IntoResponse {
    let mut tasks = state.tasks.write().unwrap();
    if let Some(pos) = tasks.iter().position(|t| t.id == id) {
        tasks.remove(pos);
        StatusCode::NO_CONTENT
    } else {
        StatusCode::NOT_FOUND
    }
}

type State<T> = axum::extract::State<T>;

pub fn app() -> Router {
    let state = app_state();
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
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    async fn call_service(router: &Router, req: axum::http::Request<axum::body::Body>) -> (axum::http::StatusCode, String) {
        let response = router.oneshot(req).await.unwrap();
        let status = response.status();
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let body_str = String::from_utf8(body.to_vec()).unwrap();
        (status, body_str)
    }

    #[test]
    fn test_health() {
        let app = app();
        let request = axum::http::Request::builder()
            .uri("/health")
            .body(axum::body::Body::empty())
            .unwrap();

        let (status, body) = tokio::runtime::Runtime::new().unwrap().block_on(async {
            call_service(&app, request).await
        });

        assert_eq!(status, axum::http::StatusCode::OK);
        assert_eq!(body, r#"{"status":"ok"}"#);
    }

    #[test]
    fn test_create_task() {
        let app = app();
        let request = axum::http::Request::builder()
            .uri("/tasks")
            .method("POST")
            .header("content-type", "application/json")
            .body(axum::body::Body::from(r#"{"title":"Test task"}"#))
            .unwrap();

        let (status, body) = tokio::runtime::Runtime::new().unwrap().block_on(async {
            call_service(&app, request).await
        });

        assert_eq!(status, axum::http::StatusCode::CREATED);
        assert!(body.contains(r#""id":1"#));
        assert!(body.contains(r#""title":"Test task""#));
        assert!(body.contains(r#""done":false"#));
    }

    #[test]
    fn test_get_task_after_creation() {
        let app = app();
        // Create task first
        let create_req = axum::http::Request::builder()
            .uri("/tasks")
            .method("POST")
            .header("content-type", "application/json")
            .body(axum::body::Body::from(r#"{"title":"Get me"}"#))
            .unwrap();
        
        let (status, _) = tokio::runtime::Runtime::new().unwrap().block_on(async {
            call_service(&app, create_req).await
        });
        assert_eq!(status, axum::http::StatusCode::CREATED);

        // Now get the task
        let get_req = axum::http::Request::builder()
            .uri("/tasks/1")
            .body(axum::body::Body::empty())
            .unwrap();

        let (status, body) = tokio::runtime::Runtime::new().unwrap().block_on(async {
            call_service(&app, get_req).await
        });

        assert_eq!(status, axum::http::StatusCode::OK);
        assert!(body.contains(r#""id":1"#));
        assert!(body.contains(r#""title":"Get me""#));
    }

    #[test]
    fn test_get_nonexistent_task() {
        let app = app();
        let request = axum::http::Request::builder()
            .uri("/tasks/999")
            .body(axum::body::Body::empty())
            .unwrap();

        let (status, _) = tokio::runtime::Runtime::new().unwrap().block_on(async {
            call_service(&app, request).await
        });

        assert_eq!(status, axum::http::StatusCode::NOT_FOUND);
    }

    #[test]
    fn test_delete_task() {
        let app = app();
        // Create task
        let create_req = axum::http::Request::builder()
            .uri("/tasks")
            .method("POST")
            .header("content-type", "application/json")
            .body(axum::body::Body::from(r#"{"title":"Delete me"}"#))
            .unwrap();
        
        let (status, _) = tokio::runtime::Runtime::new().unwrap().block_on(async {
            call_service(&app, create_req).await
        });
        assert_eq!(status, axum::http::StatusCode::CREATED);

        // Delete it
        let delete_req = axum::http::Request::builder()
            .uri("/tasks/1")
            .method("DELETE")
            .body(axum::body::Body::empty())
            .unwrap();

        let (status, _) = tokio::runtime::Runtime::new().unwrap().block_on(async {
            call_service(&app, delete_req).await
        });

        assert_eq!(status, axum::http::StatusCode::NO_CONTENT);

        // Verify it's gone
        let get_req = axum::http::Request::builder()
            .uri("/tasks/1")
            .body(axum::body::Body::empty())
            .unwrap();

        let (status, _) = tokio::runtime::Runtime::new().unwrap().block_on(async {
            call_service(&app, get_req).await
        });

        assert_eq!(status, axum::http::StatusCode::NOT_FOUND);
    }
}
```