//! Reference implementation. Not given to the model — it exists so the fixture
//! can be shown to be solvable, and so tests/api.rs can be re-verified after edits.
//!
//! ```text
//! cp reference/lib.rs project/src/lib.rs && (cd project && cargo test)
//! ```

use axum::{
    extract::{Path, State},
    http::StatusCode,
    routing::get,
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::sync::{Arc, RwLock};

#[derive(Clone, Serialize, Deserialize)]
pub struct Task {
    pub id: u64,
    pub title: String,
    pub done: bool,
}

#[derive(Deserialize)]
struct CreateTask {
    title: String,
}

#[derive(Deserialize)]
struct UpdateTask {
    title: String,
    done: bool,
}

#[derive(Default)]
struct AppState {
    tasks: BTreeMap<u64, Task>,
    next_id: u64,
}

type Shared = Arc<RwLock<AppState>>;

pub fn app() -> Router {
    let state: Shared = Arc::new(RwLock::new(AppState {
        tasks: BTreeMap::new(),
        next_id: 1,
    }));

    Router::new()
        .route("/health", get(health))
        .route("/tasks", get(list).post(create))
        .route("/tasks/{id}", get(fetch).put(update).delete(remove))
        .with_state(state)
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
}

async fn list(State(state): State<Shared>) -> Json<Vec<Task>> {
    let guard = state.read().unwrap();
    Json(guard.tasks.values().cloned().collect())
}

async fn create(State(state): State<Shared>, Json(body): Json<CreateTask>) -> (StatusCode, Json<Task>) {
    let mut guard = state.write().unwrap();
    let id = guard.next_id;
    guard.next_id += 1;
    let task = Task {
        id,
        title: body.title,
        done: false,
    };
    guard.tasks.insert(id, task.clone());
    (StatusCode::CREATED, Json(task))
}

async fn fetch(Path(id): Path<u64>, State(state): State<Shared>) -> Result<Json<Task>, StatusCode> {
    let guard = state.read().unwrap();
    guard
        .tasks
        .get(&id)
        .cloned()
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}

async fn update(
    Path(id): Path<u64>,
    State(state): State<Shared>,
    Json(body): Json<UpdateTask>,
) -> Result<Json<Task>, StatusCode> {
    let mut guard = state.write().unwrap();
    match guard.tasks.get_mut(&id) {
        Some(task) => {
            task.title = body.title;
            task.done = body.done;
            Ok(Json(task.clone()))
        }
        None => Err(StatusCode::NOT_FOUND),
    }
}

async fn remove(Path(id): Path<u64>, State(state): State<Shared>) -> StatusCode {
    let mut guard = state.write().unwrap();
    if guard.tasks.remove(&id).is_some() {
        StatusCode::NO_CONTENT
    } else {
        StatusCode::NOT_FOUND
    }
}
