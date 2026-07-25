use axum::{
    extract::Path,
    http::StatusCode,
    response::Json,
    routing::{get, post, put, delete},
    Router,
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

async fn health() -> Json<std::collections::HashMap<&'static str, &'static str>> {
    let mut map = std::collections::HashMap::new();
    map.insert("status", "ok");
    Json(map)
}

async fn get_tasks(state: axum::extract::State<Arc<RwLock<AppState>>>) -> Json<Vec<Task>> {
    let state = state.read().unwrap();
    Json(state.tasks.clone())
}

async fn create_task(
    state: axum::extract::State<Arc<RwLock<AppState>>>,
    Json(payload): Json<CreateTaskRequest>,
) -> (StatusCode, Json<Task>) {
    let mut state = state.write().unwrap();
    let id = state.next_id;
    state.next_id += 1;

    let task = Task {
        id,
        title: payload.title,
        done: false,
    };
    state.tasks.push(task.clone());

    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    state: axum::extract::State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
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
    state: axum::extract::State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
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
    state: axum::extract::State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> StatusCode {
    let mut state = state.write().unwrap();
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
        .route("/tasks", get(get_tasks))
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

    async fn call_service(
        router: &Router,
        method: &str,
        path: &str,
        body: Option<String>,
    ) -> (StatusCode, String) {
        let mut request = axum::http::Request::builder()
            .method(method)
            .uri(path)
            .body(axum::body::Body::empty())
            .unwrap();

        if let Some(body_text) = body {
            *request.body_mut() = axum::body::Body::from(body_text);
        }

        let response = router.oneshot(request).await.unwrap();
        let status = response.status();
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let body_str = String::from_utf8(body.to_vec()).unwrap();
        (status, body_str)
    }

    #[tokio::test]
    async fn test_health() {
        let app = app();
        let (status, body) = call_service(&app, "GET", "/health", None).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, r#"{"status":"ok"}"#);
    }

    #[tokio::test]
    async fn test_create_task() {
        let app = app();
        let (status, body) = call_service(
            &app,
            "POST",
            "/tasks",
            Some(r#"{"title":"Test Task"}"#.to_string()),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let task: Task = serde_json::from_str(&body).unwrap();
        assert_eq!(task.id, 1);
        assert_eq!(task.title, "Test Task");
        assert!(!task.done);
    }

    #[tokio::test]
    async fn test_get_task_after_creation() {
        let app = app();
        // Create task first
        let (_, _) = call_service(
            &app,
            "POST",
            "/tasks",
            Some(r#"{"title":"Test Task"}"#.to_string()),
        )
        .await;

        let (status, body) = call_service(&app, "GET", "/tasks/1", None).await;
        assert_eq!(status, StatusCode::OK);
        let task: Task = serde_json::from_str(&body).unwrap();
        assert_eq!(task.id, 1);
    }

    #[tokio::test]
    async fn test_get_nonexistent_task() {
        let app = app();
        let (status, _) = call_service(&app, "GET", "/tasks/999", None).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task() {
        let app = app();
        // Create task first
        let (_, _) = call_service(
            &app,
            "POST",
            "/tasks",
            Some(r#"{"title":"Test Task"}"#.to_string()),
        )
        .await;

        // Delete the task
        let (status, _) = call_service(&app, "DELETE", "/tasks/1", None).await;
        assert_eq!(status, StatusCode::NO_CONTENT);

        // Verify it's gone
        let (status, _) = call_service(&app, "GET", "/tasks/1", None).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }
}
