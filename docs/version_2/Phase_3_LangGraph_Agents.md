# Phase 3: LangGraph Agentic Framework (Cloud LLMs)

## Overview
**Goal:** Build the "Memory" and "Autonomous Evaluation" loop using LangGraph in our Python microservice. To keep the laptop fast for the demo, we will totally avoid running Llama/Ollama locally. We will offload the pure AI thinking to the lightning-fast free **Groq API**.

**Hardware Execution Splitting:**
- **Ubuntu PC:** ❌ Not needed anymore.
- **Demo Laptop:** ✅ Python service runs here, but the thinking happens in the cloud.

---

## 🛑 What NOT to do
1. **DO NOT** install Ollama or download `.gguf` weights on the laptop. 
2. **DO NOT** perform blocking HTTP requests inside the Python AI logic. Ensure everything in FastAPI remains `async`.

---

## ✅ Task Breakdown

### 1. Obtain Cloud AI Keys (Laptop - Browser)
- [ ] Go to [console.groq.com](https://console.groq.com/) and create a free account.
- [ ] Generate an API Key. This will give us access to `Llama-3-70b` running inference in the cloud at 800 tokens per second for free.
- [ ] In `skillbridge-ai-service`, add `.env` holding `GROQ_API_KEY=your_key_here`.

### 2. LangGraph Environment Setup (Laptop - Python)
- [ ] Ensure your Terminal is inside `skillbridge-ai-service`.
- [ ] Install LangGraph and Groq integrations:
  ```bash
  pip install langgraph langchain-groq
  ```

### 3. Agent Prompts and Structure (Laptop - Python)
- [ ] Create a new file `agents.py`. We need to define the **State** mapping for the Mock Interview.
  ```python
  from typing import TypedDict, List
  class InterviewState(TypedDict):
      student_id: int
      resume_text: str
      history: List[str]
      current_question: str
      student_answer: str
      final_score: int
  ```
- [ ] **Define Node 1 (Interviewer):** Uses the Groq LLM to look at `resume_text` and output a single, highly technical `current_question`.
- [ ] **Define Node 2 (Evaluator):** Uses the Groq LLM to read the `current_question` + `student_answer` and determine a `final_score` (1-5).
- [ ] **Define Routing (Condition):** If the LLM indicates the answer was missing detail, loop back and ask a follow-up. If it is satisfactory, end the graph.

### 4. Build the Graph Engine (Laptop - Python)
- [ ] In `graph.py`, wire the nodes together using LangGraph's `StateGraph`. Compile the graph.

### 5. Wire the Event Bus to the AI Trigger (Laptop - Full Stack)
- [ ] **React Frontend:** Add a "Start Mock Interview" button to the Student Dashboard running on `http://localhost:5173`. We use the student login: `student1@skillbridge.com` (use sample data).
- [ ] **Spring Boot Backend:** Receives the REST request and drops a `START_INTERVIEW` event into CloudAMQP.
- [ ] **Python Execution:** The Python Pika consumer picks up the AMQP message, extracts the `student_id`, and executes the LangGraph chain. 
- [ ] **Callback Webhook:** Once LangGraph outputs the `final_score`, write a simple `requests.post` inside Python that hits the Java Backend to update the database table `topic_progress` or `student_skills`.
