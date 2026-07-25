from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List


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
    tasks: dict[int, dict] = {}
    next_id: int = 1

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.get("/tasks", response_model=List[Task])
    async def list_tasks():
        return sorted(tasks.values(), key=lambda t: t["id"])

    @app.post("/tasks", status_code=201, response_model=Task)
    async def create_task(data: TaskCreate):
        nonlocal next_id
        task = {"id": next_id, "title": data.title, "done": False}
        tasks[next_id] = task
        next_id += 1
        return task

    @app.get("/tasks/{task_id}", response_model=Task)
    async def get_task(task_id: int):
        if task_id not in tasks:
            raise HTTPException(status_code=404)
        return tasks[task_id]

    @app.put("/tasks/{task_id}", response_model=Task)
    async def update_task(task_id: int, data: TaskUpdate):
        if task_id not in tasks:
            raise HTTPException(status_code=404)
        tasks[task_id].update({"title": data.title, "done": data.done})
        return tasks[task_id]

    @app.delete("/tasks/{task_id}", status_code=204)
    async def delete_task(task_id: int):
        if task_id not in tasks:
            raise HTTPException(status_code=404)
        del tasks[task_id]

    return app


app = create_app()
