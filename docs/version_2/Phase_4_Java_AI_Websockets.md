# Phase 4: Java AI Integration & Real-time WebSockets

## Overview
**Goal:** To make the application feel truly premium, users should never have to manually refresh the page. We will implement WebSockets to push UI notifications instantly after long-running AI processes finish. We will also integrate `Spring AI` into the core Java backend to handle lightweight admin tasks natively.

**Hardware Execution Splitting:**
- **Ubuntu PC:** ❌ Not needed.
- **Demo Laptop:** ✅ Full Stack execution (React + Spring Boot).

**Execution Commands Reminder:**
- React: `cd skillbridge-frontend` -> `npm run dev`
- Spring Boot: `mvn spring-boot:run`

---

## 🛑 What NOT to do
1. **DO NOT** use default long-polling to check for AI status; firmly stick to binary WebSocket connections.
2. **DO NOT** leak your Groq/Gemini API keys via React. All AI calls MUST originate from the Python or Java backend securely.

---

## ✅ Task Breakdown

### 1. Spring Boot WebSockets (Laptop - Backend)
- [ ] **Dependency Update:** Add `spring-boot-starter-websocket` to your Backend `pom.xml`.
- [ ] **Configuration:** Create `WebSocketConfig.java` implementing `WebSocketMessageBrokerConfigurer`.
- [ ] Enable the STOMP Broker (e.g., `/topic/student-updates`) and register the WebSocket endpoint (e.g., `/ws`).
- [ ] Override CORS explicitly in the WebSocket config to accept connections from `http://localhost:5173`.

### 2. Triggering Socket Events (Laptop - Backend)
- [ ] Remember in Phase 3 where Python hit a Java Webhook upon evaluating an interview?
- [ ] In the Java controller that receives that Webhook, inject `SimpMessagingTemplate`.
- [ ] Whenever an update is written to the database, actively push a JSON message to a dynamically routed user queue explicitly: `/topic/notifications/{studentId}`.

### 3. React Frontend Notification Subscribe (Laptop - Frontend)
- [ ] **Install Packages:** Open terminal in `/skillbridge-frontend` and run:
  ```bash
  npm install @stomp/stompjs
  ```
- [ ] **Store Integration:** Use React `useEffect` in the global Layout component to instantiate the STOMP client against `ws://localhost:8080/ws`.
- [ ] Ensure the component dynamically subscribes to `/topic/notifications/{current_user_id}` depending on who is logged in.
- [ ] When a socket message arrives, fire a Radix UI `shadcn` Toast notification on the screen: *"Your Mock Interview has been graded. New Java Proficiency Level: 4"*.

### 4. Admin Management via Spring AI (Laptop - Backend)
- [ ] Add `spring-ai-openai-spring-boot-starter` (or custom Groq client) to pom.
- [ ] In the College Admin portal, under Batch Creation, create an endpoint: `Auto-Recommend Trainer`.
- [ ] Java explicitly pulls the newly proposed syllabus topics and the profiles of 15 available trainers.
- [ ] Java strings them together and sends an explicit system prompt to the cloud LLM using `ChatClient`: *"Which trainer is statistically optimal to teach this batch based on their skills?"*
- [ ] Return the generated string natively via REST response to the College Admin dashboard on React. 

### 5. Final Demonstration Walkthrough
- [ ] Start Spring Boot (`mvn spring-boot:run`).
- [ ] Start React App (`npm run dev`).
- [ ] Start Python Service (`uvicorn main:app`).
- [ ] **Action:** Log in as existing student or trainer. Click the AI feature. Watch the frontend instantly reflect the background asynchronous multi-agent processing. Ensure zero lag present on your presentation laptop!
