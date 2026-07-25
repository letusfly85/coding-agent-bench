### FILE: app/__init__.py
```python
```

### FILE: app/main.py
```python
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


class TaskCreate(BaseModel):
    title: str


class TaskUpdate(BaseModel):
    title: str
    done: bool


class Task(BaseModel):
    id: int
    title: str
    done: bool


def create_app() -> FastAPI:
    app = FastAPI()
    tasks: dict[int, Task] = {}
    next_id = 1

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.get("/tasks")
    async def list_tasks():
        return sorted(tasks.values(), key=lambda t: t.id)

    @app.post("/tasks", status_code=201)
    async def create_task(task_in: TaskCreate):
        nonlocal next_id
        task = Task(id=next_id, title=task_in.title, done=False)
        tasks[next_id] = task
        next_id += 1
        return task

    @app.get("/tasks/{task_id}")
    async def get_task(task_id: int):
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Not found")
        return tasks[task_id]

    @app.put("/tasks/{task_id}")
    async def update_task(task_id: int, task_in: TaskUpdate):
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Not found")
        updated = Task(id=task_id, title=task_in.title, done=task_in.done)
        tasks[task_id] = updated
        return updated

    @app.delete("/tasks/{task_id}", status_code=204)
    async def delete_task(task_id: int):
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Not found")
        del tasks[task_id]
        return None

    return app


app = create_app()
```

### FILE: tests/test_api.py
```python
import pytest
from fastapi.testclient import TestClient
from app.main import create_app


@pytest.fixture
def client():
    app = create_app()
    return TestClient(app)


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_create_task(client):
    r = client.post("/tasks", json={"title": "First task"})
    assert r.status_code == 201
    data = r.json()
    assert data["id"] == 1
    assert data["title"] == "First task"
    assert data["done"] is False


def test_get_task_after_creation(client):
    client.post("/tasks", json={"title": "Task one"})
    r = client.get("/tasks/1")
    assert r.status_code == 200
    assert r.json()["title"] == "Task one"


def test_get_task_not_found(client):
    r = client.get("/tasks/999")
    assert r.status_code == 404


def test_delete_task(client):
    client.post("/tasks", json={"title": "To delete"})
    r = client.delete("/tasks/1")
    assert r.status_code == 204
    r = client.get("/tasks/1")
    assert r.status_code == 404
```