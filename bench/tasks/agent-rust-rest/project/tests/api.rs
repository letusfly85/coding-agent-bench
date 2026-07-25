//! Specification for task_api. Do not modify.
//!
//! Responses are inspected as untyped JSON so that the implementation is free to
//! name its own types; the only thing `src/lib.rs` must expose is `app()`.

use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;

async fn call(app: axum::Router, method: &str, uri: &str, body: Option<Value>) -> (StatusCode, Value) {
    let builder = Request::builder().method(method).uri(uri);
    let req = match body {
        Some(v) => builder
            .header("content-type", "application/json")
            .body(Body::from(v.to_string()))
            .unwrap(),
        None => builder.body(Body::empty()).unwrap(),
    };
    let res = app.oneshot(req).await.unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    let value = if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).unwrap_or(Value::Null)
    };
    (status, value)
}

#[tokio::test]
async fn health_returns_ok() {
    let (status, body) = call(task_api::app(), "GET", "/health", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "ok");
}

#[tokio::test]
async fn create_returns_201_and_id_1() {
    let (status, body) = call(
        task_api::app(),
        "POST",
        "/tasks",
        Some(json!({"title": "first"})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(body["id"], 1);
    assert_eq!(body["title"], "first");
    assert_eq!(body["done"], false);
}

#[tokio::test]
async fn get_after_create() {
    let app = task_api::app();
    call(app.clone(), "POST", "/tasks", Some(json!({"title": "get me"}))).await;

    let (status, body) = call(app, "GET", "/tasks/1", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["title"], "get me");
}

#[tokio::test]
async fn get_missing_returns_404() {
    let (status, _) = call(task_api::app(), "GET", "/tasks/999", None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn delete_then_get_returns_404() {
    let app = task_api::app();
    call(app.clone(), "POST", "/tasks", Some(json!({"title": "delete me"}))).await;

    let (status, _) = call(app.clone(), "DELETE", "/tasks/1", None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    let (status, _) = call(app, "GET", "/tasks/1", None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn list_is_sorted_by_id() {
    let app = task_api::app();
    for title in ["a", "b", "c"] {
        call(app.clone(), "POST", "/tasks", Some(json!({"title": title}))).await;
    }
    let (status, body) = call(app, "GET", "/tasks", None).await;
    assert_eq!(status, StatusCode::OK);
    let arr = body.as_array().expect("expected a JSON array");
    assert_eq!(arr.len(), 3);
    assert_eq!(arr[0]["id"], 1);
    assert_eq!(arr[1]["id"], 2);
    assert_eq!(arr[2]["id"], 3);
}

#[tokio::test]
async fn update_replaces_fields() {
    let app = task_api::app();
    call(app.clone(), "POST", "/tasks", Some(json!({"title": "before"}))).await;

    let (status, body) = call(
        app.clone(),
        "PUT",
        "/tasks/1",
        Some(json!({"title": "after", "done": true})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["title"], "after");
    assert_eq!(body["done"], true);

    let (status, _) = call(
        app,
        "PUT",
        "/tasks/999",
        Some(json!({"title": "x", "done": false})),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}
