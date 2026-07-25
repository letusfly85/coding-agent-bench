from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


class TaskCreate(BaseModel):
    title: str


class TaskUpdate(BaseModel):
    title: str
    done: bool


class TaskResponse(BaseModel):
    id: int
    title: str
    done: bool


def create_app() -> FastAPI:
    app = FastAPI()
    tasks: dict[int, TaskResponse] = {}
    next_id = 1

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.get("/tasks")
    async def list_tasks():
        return sorted(tasks.values(), key=lambda t: t.id)

    @app.post("/tasks", status_code=201)
    async def create_task(body: TaskCreate):
        nonlocal next_id
        task = TaskResponse(id=next_id, title=body.title, done=False)
        tasks[next_id] = task
        next_id += 1
        return task

    @app.get("/tasks/{task_id}")
    async def get_task(task_id: int):
        task = tasks.get(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="Not found")
        return task

    @app.put("/tasks/{task_id}")
    async def update_task(task_id: int, body: TaskUpdate):
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Not found")
        updated = TaskResponse(id=task_id, title=body.title, done=body.done)
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
