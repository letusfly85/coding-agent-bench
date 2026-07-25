use axum::{
    extract::State,
    http::StatusCode,
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

#[derive(Debug, Clone)]
struct AppState {
    tasks: Arc<RwLock<Vec<Task>>>,
    next_id: Arc<RwLock<u64>>,
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

fn app_with_state(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/tasks", get(list_tasks).post(create_task))
        .route("/tasks/{id}", get(get_task).put(update_task).delete(delete_task))
        .with_state(state)
}

pub fn app() -> Router {
    let state = AppState {
        tasks: Arc::new(RwLock::new(Vec::new())),
        next_id: Arc::new(RwLock::new(1)),
    };
    app_with_state(state)
}

async fn health() -> Json<std::collections::HashMap<&'static str, &'static str>> {
    let mut map = std::collections::HashMap::new();
    map.insert("status", "ok");
    Json(map)
}

async fn list_tasks(State(state): State<AppState>) -> Json<Vec<Task>> {
    let tasks = state.tasks.read().unwrap();
    let mut sorted = tasks.clone();
    sorted.sort_by_key(|t| t.id);
    Json(sorted)
}

async fn create_task(
    State(state): State<AppState>,
    Json(req): Json<CreateTaskRequest>,
) -> (StatusCode, Json<Task>) {
    let mut next_id_guard = state.next_id.write().unwrap();
    let id = *next_id_guard;
    *next_id_guard += 1;

    let task = Task {
        id,
        title: req.title,
        done: false,
    };

    let mut tasks_guard = state.tasks.write().unwrap();
    tasks_guard.push(task.clone());

    (StatusCode::CREATED, Json(task))
}

async fn get_task(
    State(state): State<AppState>,
    axum::extract::Path(id): axum::extract::Path<u64>,
) -> Result<Json<Task>, (StatusCode, Json<String>)> {
    let tasks = state.tasks.read().unwrap();
    tasks
        .iter()
        .find(|t| t.id == id)
        .cloned()
        .map(Json)
        .ok_or((StatusCode::NOT_FOUND, Json(format!("Task {} not found", id))))
}

async fn update_task(
    State(state): State<AppState>,
    axum::extract::Path(id): axum::extract::Path<u64>,
    Json(req): Json<UpdateTaskRequest>,
) -> Result<Json<Task>, (StatusCode, Json<String>)> {
    let mut tasks_guard = state.tasks.write().unwrap();
    if let Some(task) = tasks_guard.iter_mut().find(|t| t.id == id) {
        task.title = req.title;
        task.done = req.done;
        return Ok(Json(task.clone()));
    }
    Err((
        StatusCode::NOT_FOUND,
        Json(format!("Task {} not found", id)),
    ))
}

async fn delete_task(
    State(state): State<AppState>,
    axum::extract::Path(id): axum::extract::Path<u64>,
) -> Result<StatusCode, (StatusCode, Json<String>)> {
    let mut tasks_guard = state.tasks.write().unwrap();
    if let Some(pos) = tasks_guard.iter().position(|t| t.id == id) {
        tasks_guard.remove(pos);
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err((
            StatusCode::NOT_FOUND,
            Json(format!("Task {} not found", id)),
        ))
    }
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
    use hyper::{body::Bytes, Request};
    use tower::ServiceExt;

    async fn call_service(app: &Router, req: Request<Full<Bytes>>) -> hyper::Response<Full<Bytes>> {
        app.clone()
            .oneshot(req)
            .await
            .expect("Request failed")
    }

    #[tokio::test]
    async fn test_health() {
        let app = app();
        let req = Request::builder()
            .uri("/health")
            .body(Full::default())
            .unwrap();
        let res = call_service(&app, req).await;

        assert_eq!(res.status(), StatusCode::OK);
        let body = hyper::body::to_bytes(res.into_body()).await.unwrap();
        assert_eq!(String::from_utf8_lossy(&body), r#"{"status":"ok"}"#);
    }

    #[tokio::test]
    async fn test_create_task() {
        let app = app();
        let req = Request::builder()
            .uri("/tasks")
            .method("POST")
            .header("content-type", "application/json")
            .body(Full::from_json([("title", "Test Task")].into_iter()))
            .unwrap();
        let res = call_service(&app, req).await;

        assert_eq!(res.status(), StatusCode::CREATED);
        let body = hyper::body::to_bytes(res.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(json["id"], 1);
        assert_eq!(json["title"], "Test Task");
        assert_eq!(json["done"], false);
    }

    #[tokio::test]
    async fn test_get_task_after_creation() {
        let app = app();
        
        // Create task first
        let create_req = Request::builder()
            .uri("/tasks")
            .method("POST")
            .header("content-type", "application/json")
            .body(Full::from_json([("title", "Get Me")].into_iter()))
            .unwrap();
        call_service(&app, create_req).await;

        // Now get the task
        let req = Request::builder()
            .uri("/tasks/1")
            .body(Full::default())
            .unwrap();
        let res = call_service(&app, req).await;

        assert_eq!(res.status(), StatusCode::OK);
        let body = hyper::body::to_bytes(res.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(json["id"], 1);
        assert_eq!(json["title"], "Get Me");
        assert_eq!(json["done"], false);
    }

    #[tokio::test]
    async fn test_get_nonexistent_task() {
        let app = app();
        let req = Request::builder()
            .uri("/tasks/999")
            .body(Full::default())
            .unwrap();
        let res = call_service(&app, req).await;

        assert_eq!(res.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_delete_task() {
        let app = app();

        // Create task first
        let create_req = Request::builder()
            .uri("/tasks")
            .method("POST")
            .header("content-type", "application/json")
            .body(Full::from_json([("title", "Delete Me")].into_iter()))
            .unwrap();
        call_service(&app, create_req).await;

        // Delete it
        let delete_req = Request::builder()
            .uri("/tasks/1")
            .method("DELETE")
            .body(Full::default())
            .unwrap();
        let res = call_service(&app, delete_req).await;
        assert_eq!(res.status(), StatusCode::NO_CONTENT);

        // Try to get it again
        let get_req = Request::builder()
            .uri("/tasks/1")
            .body(Full::default())
            .unwrap();
        let get_res = call_service(&app, get_req).await;
        assert_eq!(get_res.status(), StatusCode::NOT_FOUND);
    }
}
