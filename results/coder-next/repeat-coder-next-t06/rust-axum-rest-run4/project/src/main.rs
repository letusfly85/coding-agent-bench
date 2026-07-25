use axum::{
    extract::Path,
    http::StatusCode,
    response::Json,
    routing::{delete, get, post, put},
    Router,
};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock};

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

async fn health() -> Json<std::collections::HashMap<&'static str, &'static str>> {
    let mut map = std::collections::HashMap::new();
    map.insert("status", "ok");
    Json(map)
}

async fn list_tasks(state: axum::extract::State<Arc<RwLock<AppState>>>) -> Json<Vec<Task>> {
    let state = state.read().unwrap();
    Json(state.tasks.clone())
}

async fn create_task(
    axum::extract::State(state): axum::extract::State<Arc<RwLock<AppState>>>,
    Json(payload): Json<CreateTaskRequest>,
) -> (StatusCode, Json<Task>) {
    let mut state = state.write().unwrap();
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
    Path(id): Path<u64>,
    state: axum::extract::State<Arc<RwLock<AppState>>>,
) -> Result<Json<Task>, StatusCode> {
    let state = state.read().unwrap();
    state
        .tasks
        .iter()
        .find(|t| t.id == id)
        .cloned()
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}

async fn update_task(
    Path(id): Path<u64>,
    axum::extract::State(state): axum::extract::State<Arc<RwLock<AppState>>>,
    Json(payload): Json<UpdateTaskRequest>,
) -> Result<Json<Task>, StatusCode> {
    let mut state = state.write().unwrap();
    if let Some(task) = state.tasks.iter_mut().find(|t| t.id == id) {
        task.title = payload.title;
        task.done = payload.done;
        return Ok(Json(task.clone()));
    }
    Err(StatusCode::NOT_FOUND)
}

async fn delete_task(
    Path(id): Path<u64>,
    axum::extract::State(state): axum::extract::State<Arc<RwLock<AppState>>>,
) -> Result<StatusCode, StatusCode> {
    let mut state = state.write().unwrap();
    if let Some(pos) = state.tasks.iter().position(|t| t.id == id) {
        state.tasks.remove(pos);
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(StatusCode::NOT_FOUND)
    }
}

pub fn app() -> axum::Router {
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
    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], 3000));
    axum::Server::bind(&addr)
        .serve(app.into_make_service())
        .await
        .unwrap();
}

#[cfg(test)]
mod tests {
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    use super::*;

    #[tokio::test]
    async fn health_check() {
        let app = app();
        let request = http::Request::get("/health").body(axum::body::Body::empty()).unwrap();
        let response = app.clone().oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(&*body, br#"{"status":"ok"}"#);
    }

    #[tokio::test]
    async fn create_task() {
        let app = app();
        let request = http::Request::post("/tasks")
            .header("Content-Type", "application/json")
            .body(axum::body::Body::from(r#"{"title":"Test Task"}"#))
            .unwrap();
        let response = app.oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let task: Task = serde_json::from_slice(&*body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Test Task");
        assert!(!task.done);
    }

    #[tokio::test]
    async fn get_task_after_creation() {
        let app = app();
        // First create a task
        let create_request = http::Request::post("/tasks")
            .header("Content-Type", "application/json")
            .body(axum::body::Body::from(r#"{"title":"Get Me"}"#))
            .unwrap();
        let _ = app.clone().oneshot(create_request).await.unwrap();

        // Then get it
        let get_request = http::Request::get("/tasks/1").body(axum::body::Body::empty()).unwrap();
        let response = app.oneshot(get_request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let task: Task = serde_json::from_slice(&*body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Get Me");
    }

    #[tokio::test]
    async fn get_nonexistent_task() {
        let app = app();
        let request = http::Request::get("/tasks/999").body(axum::body::Body::empty()).unwrap();
        let response = app.oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn delete_task_and_verify() {
        let app = app();
        // First create a task
        let create_request = http::Request::post("/tasks")
            .header("Content-Type", "application/json")
            .body(axum::body::Body::from(r#"{"title":"Delete Me"}"#))
            .unwrap();
        let _ = app.clone().oneshot(create_request).await.unwrap();

        // Then delete it
        let delete_request = http::Request::delete("/tasks/1").body(axum::body::Body::empty()).unwrap();
        let response = app.clone().oneshot(delete_request).await.unwrap();
        assert_eq!(response.status(), StatusCode::NO_CONTENT);

        // Verify it's gone
        let get_request = http::Request::get("/tasks/1").body(axum::body::Body::empty()).unwrap();
        let response = app.oneshot(get_request).await.unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
}
