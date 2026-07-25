Implement a small REST API in Rust using axum.

## Requirements

Crate name: `task_api`

### Dependencies
Use exactly these versions:
- `axum = "0.8"`
- `tokio = { version = "1", features = ["full"] }`
- `serde = { version = "1", features = ["derive"] }`
- `serde_json = "1"`
- dev-dependency: `tower = { version = "0.5", features = ["util"] }`
- dev-dependency: `http-body-util = "0.1"`

### Data model
```rust
struct Task {
    id: u64,
    title: String,
    done: bool,
}
```
`id` is assigned by the server, starting at 1 and incrementing.

### Endpoints
| Method | Path | Behaviour |
|---|---|---|
| GET | `/health` | 200, body `{"status":"ok"}` |
| GET | `/tasks` | 200, JSON array of all tasks, ordered by ascending id |
| POST | `/tasks` | Body `{"title":"..."}`. Creates a task with `done=false`. Returns 201 with the created task. |
| GET | `/tasks/{id}` | 200 with the task, or 404 if absent |
| PUT | `/tasks/{id}` | Body `{"title":"...","done":true}`. Returns 200 with the updated task, or 404 if absent. |
| DELETE | `/tasks/{id}` | 204 if deleted, 404 if absent |

### Implementation constraints
- In-memory state only. Use `Arc<RwLock<...>>` from `std::sync` or `tokio::sync`.
- Expose a function `pub fn app() -> axum::Router` that builds the router with fresh state, so that tests can call it directly.
- `main` binds to `0.0.0.0:3000` and serves `app()`.
- The code must compile with no warnings that would fail `cargo build`.

### Tests
Include integration tests in `src/main.rs` (a `#[cfg(test)] mod tests`) that drive the router via `tower::ServiceExt::oneshot` — do **not** bind a real TCP port in tests. Cover at minimum:
1. `GET /health` returns 200
2. `POST /tasks` returns 201 and id 1
3. `GET /tasks/1` after creation returns the task
4. `GET /tasks/999` returns 404
5. `DELETE` an existing task returns 204, and a subsequent `GET` returns 404

## Output format

Output **only** the files, each introduced by a `### FILE: <relative path>` line followed by a single fenced code block. No commentary before, between, or after the files.

### FILE: Cargo.toml
```toml
...
```

### FILE: src/main.rs
```rust
...
```
