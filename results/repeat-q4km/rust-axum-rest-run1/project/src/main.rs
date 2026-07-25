use axum::{
    extract::{Path, State},
    http::StatusCode,
    routing::{delete, get, post, put},
    body::Body,
    http::Request,
    Json,
    Router,
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

async fn health_handler() -> Json<serde_json::Value> {
    Json(serde_json::json!({ "status": "ok" }))
}

async fn list_tasks_handler(
    State(state): State<Arc<RwLock<AppState>>>,
) -> Json<Vec<Task>> {
    let guard = state.read().await;
    let mut tasks: Vec<Task> = guard.tasks.values().cloned().collect();
    tasks.sort_by_key(|t| t.id);
    Json(tasks)
}

async fn create_task_handler(
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

async fn get_task_handler(
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> Result<Json<Task>, StatusCode> {
    let guard = state.read().await;
    match guard.tasks.get(&id).cloned() {
        Some(task) => Ok(Json(task)),
        None => Err(StatusCode::NOT_FOUND),
    }
}

async fn update_task_handler(
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

async fn delete_task_handler(
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
        .route("/health", get(health_handler))
        .route(
            "/tasks",
            get(list_tasks_handler).post(create_task_handler),
        )
        .route(
            "/tasks/{id}",
            get(get_task_handler)
                .put(update_task_handler)
                .delete(delete_task_handler),
        )
        .with_state(state)
}

#[tokio::main]
async fn main() {
    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    axum::serve(listener, app()).await.unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    #[tokio::test]
    async fn test_health_returns_200() {
        let app = app();
        let req = Request::builder()
            .method("GET")
            .uri("/health")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn test_create_task_returns_201_and_id_1() {
        let app = app();
        let body = serde_json::to_string(&CreateTaskRequest {
            title: "First task".to_string(),
        })
        .unwrap();
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("content-type", "application/json")
            .body(Body::from(body))
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::CREATED);
        let bytes = res.into_body().collect().await.unwrap().to_bytes();
        let task: Task = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(task.id, 1);
    }

    #[tokio::test]
    async fn test_get_task_after_creation() {
        let app = app();
        let body = serde_json::to_string(&CreateTaskRequest {
            title: "Task 1".to_string(),
        })
        .unwrap();
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("content-type", "application/json")
            .body(Body::from(body))
            .unwrap();
        app.clone().oneshot(req).await.unwrap();

        let req = Request::builder()
            .method("GET")
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn test_get_nonexistent_task_returns_404() {
        let app = app();
        let req = Request::builder()
            .method("GET")
            .uri("/tasks/999")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task_returns_204_and_subsequent_get_returns_404() {
        let app = app();
        let body = serde_json::to_string(&CreateTaskRequest {
            title: "To delete".to_string(),
        })
        .unwrap();
        let req = Request::builder()
            .method("POST")
            .uri("/tasks")
            .header("content-type", "application/json")
            .body(Body::from(body))
            .unwrap();
        app.clone().oneshot(req).await.unwrap();

        let req = Request::builder()
            .method("DELETE")
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::NO_CONTENT);

        let req = Request::builder()
            .method("GET")
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::NOT_FOUND);
    }
}
