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
    Json, Path, Router, State,
    routing::{delete, get, post, put},
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

pub fn app() -> axum::Router {
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