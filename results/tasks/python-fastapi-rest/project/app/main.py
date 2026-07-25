from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


class TaskCreate(BaseModel):
    title: str


class TaskUpdate(BaseModel):
    title: str
    done: bool


class TaskOut(BaseModel):
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

    @app.get("/tasks")
    async def list_tasks() -> list[TaskOut]:
        return sorted(tasks.values(), key=lambda t: t["id"])

    @app.post("/tasks", status_code=201)
    async def create_task(task_in: TaskCreate) -> TaskOut:
        nonlocal next_id
        task = {"id": next_id, "title": task_in.title, "done": False}
        tasks[next_id] = task
        next_id += 1
        return task

    @app.get("/tasks/{task_id}")
    async def get_task(task_id: int) -> TaskOut:
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Not found")
        return tasks[task_id]

    @app.put("/tasks/{task_id}")
    async def update_task(task_id: int, task_in: TaskUpdate) -> TaskOut:
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Not found")
        tasks[task_id] = {"id": task_id, "title": task_in.title, "done": task_in.done}
        return tasks[task_id]

    @app.delete("/tasks/{task_id}", status_code=204)
    async def delete_task(task_id: int):
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Not found")
        del tasks[task_id]
        return None

    return app


app = create_app()
