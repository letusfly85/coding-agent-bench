use axum::{
    Router,
    Json,
    routing::{get, post, put, delete},
    extract::{State, Path},
    response::IntoResponse,
    http::StatusCode,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tokio::sync::RwLock;
use std::sync::Arc;

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

async fn health() -> impl IntoResponse {
    Json(serde_json::json!({ "status": "ok" }))
}

async fn list_tasks(State(state): State<Arc<RwLock<AppState>>>) -> impl IntoResponse {
    let state = state.read().await;
    let mut tasks: Vec<Task> = state.tasks.values().cloned().collect();
    tasks.sort_by_key(|t| t.id);
    Json(tasks)
}

async fn create_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Json(payload): Json<CreateTaskRequest>,
) -> impl IntoResponse {
    let mut state = state.write().await;
    let id = state.next_id;
    state.next_id += 1;
    let task = Task {
        id,
        title: payload.title,
        done: false,
    };
    state.tasks.insert(id, task.clone());
    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> impl IntoResponse {
    let state = state.read().await;
    match state.tasks.get(&id).cloned() {
        Some(task) => (StatusCode::OK, Json(task)).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn update_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
    Json(payload): Json<UpdateTaskRequest>,
) -> impl IntoResponse {
    let mut state = state.write().await;
    match state.tasks.get_mut(&id) {
        Some(task) => {
            task.title = payload.title;
            task.done = payload.done;
            (StatusCode::OK, Json(task.clone())).into_response()
        }
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn delete_task(
    State(state): State<Arc<RwLock<AppState>>>,
    Path(id): Path<u64>,
) -> impl IntoResponse {
    let mut state = state.write().await;
    if state.tasks.remove(&id).is_some() {
        StatusCode::NO_CONTENT.into_response()
    } else {
        StatusCode::NOT_FOUND.into_response()
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
    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    axum::serve(listener, app()).await.unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::{Request, Method};
    use axum::body::Body;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    async fn send_request(req: Request<Body>) -> (StatusCode, String) {
        let mut app = app().into_make_service();
        let resp = app.oneshot(req).await.unwrap();
        let status = resp.status();
        let body_bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let body_str = String::from_utf8(body_bytes.to_vec()).unwrap();
        (status, body_str)
    }

    #[tokio::test]
    async fn test_health() {
        let req = Request::builder()
            .method(Method::GET)
            .uri("/health")
            .body(Body::empty())
            .unwrap();
        let (status, body) = send_request(req).await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.contains("\"status\":\"ok\""));
    }

    #[tokio::test]
    async fn test_create_task() {
        let req = Request::builder()
            .method(Method::POST)
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(r#"{"title":"Test task"}"#))
            .unwrap();
        let (status, body) = send_request(req).await;
        assert_eq!(status, StatusCode::CREATED);
        assert!(body.contains("\"id\":1"));
        assert!(body.contains("\"title\":\"Test task\""));
        assert!(body.contains("\"done\":false"));
    }

    #[tokio::test]
    async fn test_get_task() {
        let req = Request::builder()
            .method(Method::POST)
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(r#"{"title":"Task 1"}"#))
            .unwrap();
        let (status, _) = send_request(req).await;
        assert_eq!(status, StatusCode::CREATED);

        let req = Request::builder()
            .method(Method::GET)
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let (status, body) = send_request(req).await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.contains("\"id\":1"));
    }

    #[tokio::test]
    async fn test_get_nonexistent_task() {
        let req = Request::builder()
            .method(Method::GET)
            .uri("/tasks/999")
            .body(Body::empty())
            .unwrap();
        let (status, _) = send_request(req).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task() {
        let req = Request::builder()
            .method(Method::POST)
            .uri("/tasks")
            .header("Content-Type", "application/json")
            .body(Body::from(r#"{"title":"To delete"}"#))
            .unwrap();
        let (status, _) = send_request(req).await;
        assert_eq!(status, StatusCode::CREATED);

        let req = Request::builder()
            .method(Method::DELETE)
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let (status, _) = send_request(req).await;
        assert_eq!(status, StatusCode::NO_CONTENT);

        let req = Request::builder()
            .method(Method::GET)
            .uri("/tasks/1")
            .body(Body::empty())
            .unwrap();
        let (status, _) = send_request(req).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }
}
