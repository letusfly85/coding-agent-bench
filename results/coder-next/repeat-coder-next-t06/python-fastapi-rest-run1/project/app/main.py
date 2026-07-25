from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional

class Task(BaseModel):
    id: int
    title: str
    done: bool

class TaskCreate(BaseModel):
    title: str

class TaskUpdate(BaseModel):
    title: str
    done: bool

class AppState:
    def __init__(self):
        self.tasks: List[Task] = []
        self.next_id: int = 1

def create_app() -> FastAPI:
    app = FastAPI()
    state = AppState()
    
    @app.get("/health")
    def health():
        return {"status": "ok"}
    
    @app.get("/tasks")
    def get_tasks():
        return state.tasks
    
    @app.post("/tasks", status_code=201)
    def create_task(task_create: TaskCreate):
        task = Task(
            id=state.next_id,
            title=task_create.title,
            done=False
        )
        state.next_id += 1
        state.tasks.append(task)
        return task
    
    @app.get("/tasks/{task_id}")
    def get_task(task_id: int):
        for task in state.tasks:
            if task.id == task_id:
                return task
        raise HTTPException(status_code=404, detail="Task not found")
    
    @app.put("/tasks/{task_id}")
    def update_task(task_id: int, task_update: TaskUpdate):
        for i, task in enumerate(state.tasks):
            if task.id == task_id:
                updated_task = Task(
                    id=task_id,
                    title=task_update.title,
                    done=task_update.done
                )
                state.tasks[i] = updated_task
                return updated_task
        raise HTTPException(status_code=404, detail="Task not found")
    
    @app.delete("/tasks/{task_id}", status_code=204)
    def delete_task(task_id: int):
        for i, task in enumerate(state.tasks):
            if task.id == task_id:
                state.tasks.pop(i)
                return
        raise HTTPException(status_code=404, detail="Task not found")
    
    return app

app = create_app()
