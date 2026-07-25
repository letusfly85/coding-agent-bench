from typing import List, Optional
from fastapi import FastAPI, HTTPException, status
from pydantic import BaseModel

class Task(BaseModel):
    id: int
    title: str
    done: bool

class TaskCreate(BaseModel):
    title: str

class TaskUpdate(BaseModel):
    title: str
    done: bool

class HealthCheck(BaseModel):
    status: str

# In-memory storage
tasks: List[Task] = []
next_id: int = 1

app = FastAPI()

def create_app() -> FastAPI:
    """Create a new FastAPI app instance with fresh state."""
    new_app = FastAPI()
    
    # Reset state
    new_tasks: List[Task] = []
    new_next_id: int = 1
    
    @new_app.get("/health", response_model=HealthCheck)
    def health():
        return {"status": "ok"}
    
    @new_app.get("/tasks", response_model=List[Task])
    def get_tasks():
        return new_tasks
    
    @new_app.post("/tasks", response_model=Task, status_code=status.HTTP_201_CREATED)
    def create_task(task_create: TaskCreate):
        nonlocal new_next_id
        task = Task(id=new_next_id, title=task_create.title, done=False)
        new_next_id += 1
        new_tasks.append(task)
        return task
    
    @new_app.get("/tasks/{task_id}", response_model=Task)
    def get_task(task_id: int):
        for task in new_tasks:
            if task.id == task_id:
                return task
        raise HTTPException(status_code=404, detail="Task not found")
    
    @new_app.put("/tasks/{task_id}", response_model=Task)
    def update_task(task_id: int, task_update: TaskUpdate):
        for task in new_tasks:
            if task.id == task_id:
                task.title = task_update.title
                task.done = task_update.done
                return task
        raise HTTPException(status_code=404, detail="Task not found")
    
    @new_app.delete("/tasks/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
    def delete_task(task_id: int):
        nonlocal new_tasks
        for i, task in enumerate(new_tasks):
            if task.id == task_id:
                new_tasks.pop(i)
                return
        raise HTTPException(status_code=404, detail="Task not found")
    
    return new_app

# Create the default app instance
app = create_app()
