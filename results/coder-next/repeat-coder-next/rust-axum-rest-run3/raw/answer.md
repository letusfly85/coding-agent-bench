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
    http::{Method, StatusCode},
    response::IntoResponse,
    routing::{get, post, put, delete},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock};

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Task {
    id: u64,
    title: String,
    done: bool,
}

#[derive(Debug, Deserialize)]
struct CreateTask {
    title: String,
}

#[derive(Debug, Deserialize)]
struct UpdateTask {
    title: String,
    done: bool,
}

type TasksStore = Arc<RwLock<Vec<Task>>>;

fn get_next_id(tasks: &RwLock<Vec<Task>>) -> u64 {
    let tasks_guard = tasks.read().unwrap();
    if tasks_guard.is_empty() {
        1
    } else {
        tasks_guard.last().unwrap().id + 1
    }
}

fn find_task(tasks: &RwLock<Vec<Task>>, id: u64) -> Option<usize> {
    let tasks_guard = tasks.read().unwrap();
    tasks_guard.iter().position(|t| t.id == id)
}

fn app_with_state(state: TasksStore) -> Router {
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
    let state = Arc::new(RwLock::new(Vec::new()));
    app_with_state(state)
}

async fn health() -> Json<std::collections::HashMap<&'static str, &'static str>> {
    let mut map = std::collections::HashMap::new();
    map.insert("status", "ok");
    Json(map)
}

async fn list_tasks(state: axum::extract::State<TasksStore>) -> Json<Vec<Task>> {
    let tasks_guard = state.read().unwrap();
    let tasks: Vec<Task> = tasks_guard.clone().into_iter().collect();
    Json(tasks)
}

async fn create_task(
    state: axum::extract::State<TasksStore>,
    Json(payload): Json<CreateTask>,
) -> (StatusCode, Json<Task>) {
    let mut tasks_guard = state.write().unwrap();
    let id = get_next_id(&state);
    let task = Task {
        id,
        title: payload.title,
        done: false,
    };
    tasks_guard.push(task.clone());
    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    Path(id): Path<u64>,
    state: axum::extract::State<TasksStore>,
) -> Result<Json<Task>, StatusCode> {
    let tasks_guard = state.read().unwrap();
    if let Some(task) = tasks_guard.iter().find(|t| t.id == id) {
        Ok(Json(task.clone()))
    } else {
        Err(StatusCode::NOT_FOUND)
    }
}

async fn update_task(
    Path(id): Path<u64>,
    state: axum::extract::State<TasksStore>,
    Json(payload): Json<UpdateTask>,
) -> Result<Json<Task>, StatusCode> {
    let mut tasks_guard = state.write().unwrap();
    if let Some(pos) = tasks_guard.iter().position(|t| t.id == id) {
        tasks_guard[pos] = Task {
            id,
            title: payload.title,
            done: payload.done,
        };
        Ok(Json(tasks_guard[pos].clone()))
    } else {
        Err(StatusCode::NOT_FOUND)
    }
}

async fn delete_task(
    Path(id): Path<u64>,
    state: axum::extract::State<TasksStore>,
) -> Result<StatusCode, StatusCode> {
    let mut tasks_guard = state.write().unwrap();
    if let Some(pos) = tasks_guard.iter().position(|t| t.id == id) {
        tasks_guard.remove(pos);
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(StatusCode::NOT_FOUND)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use http::{Request, Response};
    use http_body_util::Full;
    use hyper::Body as HyperBody;
    use tower::ServiceExt;

    async fn call_service(router: &Router, req: Request<Body>) -> Response<HyperBody> {
        router.clone().oneshot(req).await.unwrap()
    }

    #[tokio::test]
    async fn test_health() {
        let app = app();
        let request = Request::builder()
            .method(Method::GET)
            .uri("/health")
            .body(Body::empty())
            .unwrap();
        let response = call_service(&app, request).await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = hyper::body::to_bytes(response.into_body()).await.unwrap();
        let body_str = std::str::from_utf8(&body).unwrap();
        assert_eq!(body_str, r#"{"status":"ok"}"#);
    }

    #[tokio::test]
    async fn test_create_task() {
        let app = app();
        let request = Request::builder()
            .method(Method::POST)
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(r#"{"title":"Test task"}"#))
            .unwrap();
        let response = call_service(&app, request).await;
        assert_eq!(response.status(), StatusCode::CREATED);
        let body = hyper::body::to_bytes(response.into_body()).await.unwrap();
        let task: Task = serde_json::from_slice(&body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Test task");
        assert!(!task.done);
    }

    #[tokio::test]
    async fn test_get_task_after_creation() {
        let app = app();
        let request = Request::builder()
            .method(Method::POST)
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(r#"{"title":"Test task"}"#))
            .unwrap();
        let _ = call_service(&app, request).await;

        let request = Request::builder()
            .method(Method::GET)
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let response = call_service(&app, request).await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = hyper::body::to_bytes(response.into_body()).await.unwrap();
        let task: Task = serde_json::from_slice(&body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Test task");
        assert!(!task.done);
    }

    #[tokio::test]
    async fn test_get_nonexistent_task() {
        let app = app();
        let request = Request::builder()
            .method(Method::GET)
            .uri("/tasks/999")
            .body(Body::empty())
            .unwrap();
        let response = call_service(&app, request).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task() {
        let app = app();
        // First create a task
        let request = Request::builder()
            .method(Method::POST)
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(r#"{"title":"To delete"}"#))
            .unwrap();
        let _ = call_service(&app, request).await;

        // Now delete it
        let request = Request::builder()
            .method(Method::DELETE)
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let response = call_service(&app, request).await;
        assert_eq!(response.status(), StatusCode::NO_CONTENT);

        // Confirm deletion
        let request = Request::builder()
            .method(Method::GET)
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let response = call_service(&app, request).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
}
```