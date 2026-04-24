# Phase 1: Microservices Decoupling & Serverless Event Bus

## Overview
**Goal:** We need to transition the current `skillbridge-backend` from a monolithic structure to an event-driven microservices architecture. We will strictly use a cloud-hosted message broker so your demonstration **Laptop** never runs heavy Docker containers.

**Current Test Credentials (Laptop):**
- **System Admin:** `r.nandakishore24@gmail.com` / `Password123!@#`
- **College Admin:** `admin@skillbridge.com` / `Password123!@#`

**Hardware Execution Splitting:**
- **Ubuntu PC:** ❌ DO NOT USE for this phase.
- **Demo Laptop:** ✅ Execution happens here.

---

## 🛑 What NOT to do
1. **DO NOT** install Docker or RabbitMQ on the laptop. 
2. **DO NOT** delete the current `skillbridge-backend` folder. We are augmenting it, not replacing it.

---

## ✅ Task Breakdown

### 1. CloudAMQP Configuration (Laptop - Browser)
- [ ] Go to [CloudAMQP.com](https://www.cloudamqp.com/) and create a free account.
- [ ] Provision a free "Lemur" RabbitMQ instance.
- [ ] Copy the connection string (AMQP URL) from the dashboard. Save it securely.

### 2. Spring Boot Setup (Laptop - `skillbridge-backend`)
- [ ] **Dependency Update:** Open `skillbridge-backend/pom.xml` and add the `spring-boot-starter-amqp` dependency.
- [ ] **Config Update:** Open `application-dev.yaml` and add the CloudAMQP properties:
  ```yaml
  spring:
    rabbitmq:
      addresses: "amqps://YOUR_CLOUD_AMQP_URL"
  ```
- [ ] **Create RabbitMQ Config File:** Create `src/main/java/com/skillbridge/common/config/RabbitMQConfig.java`. Configure an `Exchange` and a `Queue` (e.g., `ai.analysis.queue`).
- [ ] **Create Event Publisher:** In `BatchService` or `StudentService`, whenever a student alters their data, use `RabbitTemplate` to push a JSON payload (e.g., `{ "studentId": 5, "action": "PROFILE_UPDATE" }`) to the queue.
- [ ] **Test Execution:** Run `mvn spring-boot:run` and verify in the CloudAMQP dashboard that connections are established and messages arrive in the queue.

### 3. Python AI Service Skeleton (Laptop - New Directory)
- [ ] **Directory Setup:** In the root folder (`SkillBridgeV2/`), create a new directory: `mkdir skillbridge-ai-service`.
- [ ] **Environment Setup:** 
  ```bash
  cd skillbridge-ai-service
  python -m venv venv
  source venv/Scripts/activate # (Or venv/bin/activate on Mac/Bash)
  pip install fastapi uvicorn pika python-dotenv
  ```
- [ ] **Create App Scaffold:** Create `main.py` with a basic FastAPI setup.
- [ ] **Create AMQP Consumer:** Create an asynchronous worker in Python using the `pika` library that connects to your CloudAMQP URL and indefinitely listens to `ai.analysis.queue`.
- [ ] **Test Execution:** Run `uvicorn main:app --reload`. Trigger an event from the Java backend or React frontend and ensure the Python console prints: *"Received: PROFILE_UPDATE for studentId: 5"*.
