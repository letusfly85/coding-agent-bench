use axum::{
    extract::Path,
    http::StatusCode,
    response::Json,
    routing::{get, post, put, delete},
    Router,
};
use serde::{Deserialize, Serialize};
use std::{sync::Arc};
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
    tasks: Vec<Task>,
    next_id: u64,
}

impl AppState {
    fn new() -> Self {
        Self {
            tasks: Vec::new(),
            next_id: 1,
        }
    }
}

type AppData = Arc<RwLock<AppState>>;

async fn health() -> Json<{ json: &'static str }> {
    Json(serde_json::json!({"status": "ok"}))
}

async fn list_tasks(state: AppData) -> Json<Vec<Task>> {
    let state = state.read().await;
    Json(state.tasks.clone())
}

async fn create_task(
    state: AppData,
    Json(payload): Json<CreateTaskRequest>,
) -> (StatusCode, Json<Task>) {
    let mut state = state.write().await;
    let task = Task {
        id: state.next_id,
        title: payload.title,
        done: false,
    };
    state.next_id += 1;
    state.tasks.push(task.clone());
    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    state: AppData,
    Path(id): Path<u64>,
) -> Result<Json<Task>, StatusCode> {
    let state = state.read().await;
    state.tasks.iter().find(|t| t.id == id).cloned()
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}

async fn update_task(
    state: AppData,
    Path(id): Path<u64>,
    Json(payload): Json<UpdateTaskRequest>,
) -> Result<Json<Task>, StatusCode> {
    let mut state = state.write().await;
    if let Some(task) = state.tasks.iter_mut().find(|t| t.id == id) {
        task.title = payload.title;
        task.done = payload.done;
        Ok(Json(task.clone()))
    } else {
        Err(StatusCode::NOT_FOUND)
    }
}

async fn delete_task(state: AppData, Path(id): Path<u64>) -> StatusCode {
    let mut state = state.write().await;
    if let Some(pos) = state.tasks.iter().position(|t| t.id == id) {
        state.tasks.remove(pos);
        StatusCode::NO_CONTENT
    } else {
        StatusCode::NOT_FOUND
    }
}

pub fn app() -> Router {
    let state = Arc::new(RwLock::new(AppState::new()));
    
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
    use hyper::{body::Bytes, Request};
    use tower::ServiceExt;

    async fn call_service(router: &Router, req: Request<axum::body::Body>) -> (StatusCode, String) {
        let response = router.oneshot(req).await.unwrap();
        let status = response.status();
        let body = response.into_body().collect().await.unwrap();
        let body_str = String::from_utf8(body.to_bytes().to_vec()).unwrap();
        (status, body_str)
    }

    #[tokio::test]
    async fn test_health() {
        let app = app();
        let req = Request::get("/health").body(axum::body::Body::empty()).unwrap();
        let (status, body) = call_service(&app, req).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, r#"{"status":"ok"}"#);
    }

    #[tokio::test]
    async fn test_create_task() {
        let app = app();
        let req = Request::post("/tasks")
            .header("Content-Type", "application/json")
            .body(axum::body::Body::from(r#"{"title":"Test Task"}"#))
            .unwrap();
        let (status, body) = call_service(&app, req).await;
        assert_eq!(status, StatusCode::CREATED);
        assert_eq!(body, r#"{"id":1,"title":"Test Task","done":false}"#);
    }

    #[tokio::test]
    async fn test_get_task_after_creation() {
        let app = app();
        
        // Create task
        let create_req = Request::post("/tasks")
            .header("Content-Type", "application/json")
            .body(axum::body::Body::from(r#"{"title":"Get Me"}"#))
            .unwrap();
        let (status, _) = call_service(&app, create_req).await;
        assert_eq!(status, StatusCode::CREATED);
        
        // Get task
        let get_req = Request::get("/tasks/1").body(axum::body::Body::empty()).unwrap();
        let (status, body) = call_service(&app, get_req).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, r#"{"id":1,"title":"Get Me","done":false}"#);
    }

    #[tokio::test]
    async fn test_get_nonexistent_task() {
        let app = app();
        let req = Request::get("/tasks/999").body(axum::body::Body::empty()).unwrap();
        let (status, _) = call_service(&app, req).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task() {
        let app = app();
        
        // Create task
        let create_req = Request::post("/tasks")
            .header("Content-Type", "application/json")
            .body(axum::body::Body::from(r#"{"title":"Delete Me"}"#))
            .unwrap();
        let (status, _) = call_service(&app, create_req).await;
        assert_eq!(status, StatusCode::CREATED);
        
        // Delete task
        let delete_req = Request::delete("/tasks/1").body(axum::body::Body::empty()).unwrap();
        let (status, _) = call_service(&app, delete_req).await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        
        // Verify deletion
        let get_req = Request::get("/tasks/1").body(axum::body::Body::empty()).unwrap();
        let (status, _) = call_service(&app, get_req).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }
}
