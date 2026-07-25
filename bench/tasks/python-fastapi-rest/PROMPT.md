Implement a small REST API in Python using FastAPI.

## Requirements

### Dependencies
Already installed, use exactly these: `fastapi`, `pydantic` v2, `httpx`, `pytest`.
Do not add anything else and do not emit a requirements file.

### Data model
A task has:
- `id: int` — assigned by the server, starting at 1 and incrementing
- `title: str`
- `done: bool`

Use pydantic models for request and response bodies.

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
- In-memory state only.
- Expose a module-level `app` object in `app/main.py`.
- Also expose `def create_app() -> FastAPI` that returns an app with **fresh** state, so each test gets an isolated instance.
- No `if __name__ == "__main__"` server startup is required.

### Tests
Include `tests/test_api.py` using `fastapi.testclient.TestClient` against `create_app()` — do **not** bind a real TCP port. Cover at minimum:
1. `GET /health` returns 200
2. `POST /tasks` returns 201 and id 1
3. `GET /tasks/1` after creation returns the task
4. `GET /tasks/999` returns 404
5. `DELETE` an existing task returns 204, and a subsequent `GET` returns 404

## Output format

Output **only** the files, each introduced by a `### FILE: <relative path>` line followed by a single fenced code block. No commentary before, between, or after the files.

### FILE: app/__init__.py
```python
...
```

### FILE: app/main.py
```python
...
```

### FILE: tests/test_api.py
```python
...
```
