# Phase 1: COMPLETED ✅
## Event-Driven Microservices & Serverless Event Bus

**Completed On:** 2026-04-24  
**Time Taken:** ~2 hours  
**Status:** 🟢 Spring Boot + Python AI Service both running. RabbitMQ event pipeline wired.

---

## What We Built (Big Picture)

Before Phase 1, the Java backend handled everything in one place — a monolith. If an AI process needed to run (like analyzing a student's skills), Java would have to wait for it to finish, blocking the entire server.

We introduced an **Event-Driven Architecture** using a cloud-hosted **Message Broker (RabbitMQ via CloudAMQP)**. Now:

1. **Java** does its core job (saves skill to DB) and immediately **drops a message** into the cloud queue
2. **Java** goes back to serving users — zero waiting
3. **Python AI Service** picks up the message whenever it's ready and processes it independently

This is like a **Post Office** between Java and Python. They never talk directly. Java posts a letter, Python picks it up.

---

## Concepts Learned

### DNS (Domain Name System)
Like a phone book for the internet. When your app uses a URL like `aws-x.pooler.supabase.com`, your computer first asks DNS: *"What IP address is this?"* If DNS doesn't know the hostname, you get `UnknownHostException`. This is what happened when we tried the Supabase Direct Connection URL — our ISP's DNS couldn't resolve `db.ooqxedojvxetpwjldxab.supabase.co`.

### Direct Connection vs Session Pooler (Supabase)
| | Direct Connection | Session Pooler |
|---|---|---|
| Host | `db.xxx.supabase.co:5432` | `aws-xxx.pooler.supabase.com:5432` |
| Username | `postgres` | `postgres.project_ref` |
| Talks to | PostgreSQL Server directly | PgBouncer (a connection manager) |
| Problem | Blocked by our ISP's DNS | Wasn't resolving when Supabase was paused |
| Resolution | Abandoned this | Used this after Supabase fully woke up ✅ |

### JAVA_HOME
An environment variable that tells Windows where Java is installed. We had JDK-17 set in JAVA_HOME but only JDK-23 existed on disk. Fixed by running:
```powershell
[System.Environment]::SetEnvironmentVariable("JAVA_HOME","C:\Program Files\Java\jdk-23","User")
```
This is now permanent — no need to set it on every terminal.

### Spring Profiles (application-local.yaml)
Spring Boot supports multiple config files for different environments:
- `application.yaml` = shared base config (JWT secrets, RabbitMQ URL, active profile name)
- `application-local.yaml` = your personal local DB credentials (in `.gitignore`, never committed)
- `application-dev.yaml` = placeholder example for other developers

When you run `mvn spring-boot:run`, Spring reads the `active: local` setting and automatically merges `application-local.yaml` on top of the base.

### HikariCP (Connection Pool)
HikariCP is the default connection pool library in Spring Boot. Instead of opening a new database connection for every request (which is slow), it opens a fixed set of connections on startup and reuses them. You saw this in logs:
```
HikariPool-1 - Starting...
HikariPool-1 - Added connection PgConnection@3a54638b
HikariPool-1 - Start completed.
```

### Message Broker / RabbitMQ
A system that holds messages in "Queues". Producers (Java) drop messages in. Consumers (Python) pick them up. They are decoupled — neither knows about the other directly.

Key terms:
- **Exchange:** The router. Receives messages from Java and routes them to the correct queue
- **Queue (`ai.analysis.queue`):** The waiting room where messages sit
- **Routing Key:** The label on the letter telling the exchange which queue to route to
- **AMQP:** The protocol (language) that clients use to talk to RabbitMQ

### CloudAMQP (Hosted RabbitMQ)
Instead of running RabbitMQ via Docker locally (heavy on RAM), we used [CloudAMQP.com](https://cloudamqp.com) — a free hosted RabbitMQ service. We created a **Lemur (free)** instance on AWS **AP-South-1 (Mumbai)** for lowest latency from India.

### Python FastAPI
A modern, async-first Python web framework. Extremely lightweight (uses ~50MB RAM). We used it as the backbone of our AI microservice. We run a background thread inside it using Python's `threading` module to listen to RabbitMQ without blocking the HTTP server.

---

## All Errors We Hit & How We Fixed Them

| Error | Root Cause | Fix |
|---|---|---|
| `JAVA_HOME not defined` | JAVA_HOME pointed to JDK-17 which didn't exist | Set JAVA_HOME to JDK-23 permanently |
| `DataSource url not specified` | `application-local.yaml` file didn't exist (only `.example` did) | Created the actual file with real DB credentials |
| `tenant/user not found` | Supabase project was **paused** (free tier inactivity) | Went to supabase.com and clicked "Restore Project" |
| `Connection is closed (57P01)` | Supabase was **mid cold-start**, accepted then dropped connection | Waited for Supabase to fully wake up, re-ran |
| `UnknownHostException db.xxx.supabase.co` | ISP DNS cannot resolve the Direct Connection hostname | Switched back to Session Pooler URL which resolved fine |
| `uvicorn: Fatal error in launcher` | Python 3.11 shortcut was broken, packages installed in 3.12 | Used `python -m uvicorn` to bypass the broken shortcut |

---

## Files Created in Phase 1

### Java Backend (`skillbridge-backend/`)

#### `pom.xml` — Dependency Added
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```
This is the official Spring library to talk to RabbitMQ using AMQP protocol.

#### `src/main/resources/application.yaml` — RabbitMQ URL Added
```yaml
spring:
  rabbitmq:
    addresses: amqps://gvcxbqgm:***@puffin.rmq2.cloudamqp.com/gvcxbqgm
```
A single line that tells Spring Boot to auto-connect to our CloudAMQP cloud broker at startup.

#### `src/main/resources/application-local.yaml` — CREATED (was missing)
The actual local config file with real Supabase credentials. Active profile is `local` so this file is loaded alongside the base `application.yaml`.

#### `src/main/java/com/skillbridge/common/config/RabbitMQConfig.java` — NEW FILE
Configures the RabbitMQ infrastructure inside Spring:
- Creates a durable `Queue` named `ai.analysis.queue`
- Creates a `DirectExchange` named `skillbridge-ai-exchange`
- Binds the queue to the exchange via routing key `ai.analysis.route`
- Registers a `Jackson2JsonMessageConverter` so Java objects are auto-converted to JSON before sending

#### `src/main/java/com/skillbridge/shared/messaging/AIEvent.java` — NEW FILE
A Java `record` (immutable data class) that represents a standardized message payload:
```java
public record AIEvent(String eventType, Long studentId, Long collegeId, Object metadata) {}
```
A flat, self-contained object safe to serialize outside of Hibernate transaction boundaries.

#### `src/main/java/com/skillbridge/shared/messaging/AIEventPublisher.java` — NEW FILE
The **single gateway** for all RabbitMQ publishing in the whole application. Has two public methods:
- `publishSkillUpdated(studentId, collegeId, skillId)` — fires when skill is added or updated
- `publishProfileUpdated(studentId, collegeId)` — fires when student profile is edited

Critical design: Uses `try-catch` around the send. If CloudAMQP is down, it logs the error but **never crashes the main API**. AI analysis is a bonus, not a blocker.

#### `src/main/java/com/skillbridge/student/service/StudentService.java` — MODIFIED
Added `AIEventPublisher` as an injected dependency. Three hooks were added:
1. After `updateStudentProfile()` saves → `publishProfileUpdated()`
2. After `addSkill()` saves → `publishSkillUpdated()`
3. After `updateSkillProficiency()` saves → `publishSkillUpdated()`

### Python AI Service (`skillbridge-ai-service/`) — NEW DIRECTORY

#### `requirements.txt`
```
fastapi
uvicorn
pika
python-dotenv
```

#### `main.py`
- FastAPI app skeleton with a health check endpoint at `GET /`
- Background thread using `pika` library that connects to CloudAMQP
- Listens indefinitely to `ai.analysis.queue`
- When a message arrives from Java, it prints the payload to the console
- Start command: `python -m uvicorn main:app --reload`

---

## How to Run the Full Stack (Phase 1)

Open **3 separate terminals:**

**Terminal 1 — React Frontend:**
```bash
cd skillbridge-frontend
npm run dev
# Runs on http://localhost:5173
```

**Terminal 2 — Java Backend:**
```bash
cd skillbridge-backend
mvn spring-boot:run
# Runs on http://localhost:8080
```

**Terminal 3 — Python AI Service:**
```bash
cd skillbridge-ai-service
python -m uvicorn main:app --reload
# Runs on http://localhost:8000
# Also listens to CloudAMQP queue in background
```

---

## Phase 1 Checklist

- [x] CloudAMQP account created and free "SkillBridge-Bus" instance provisioned (Mumbai, AWS)
- [x] `spring-boot-starter-amqp` added to `pom.xml`
- [x] CloudAMQP AMQP URL added to `application.yaml`
- [x] `application-local.yaml` created with real Supabase credentials
- [x] JAVA_HOME permanently fixed to point to JDK-23
- [x] `RabbitMQConfig.java` created — Queue, Exchange, Binding, JSON converter
- [x] `AIEvent.java` created — Serializable event payload
- [x] `AIEventPublisher.java` created — Single isolated RabbitMQ gateway
- [x] `StudentService.java` wired — 3 AI event hooks added
- [x] `skillbridge-ai-service/` directory created from scratch
- [x] Python dependencies installed (`fastapi`, `uvicorn`, `pika`)
- [x] `main.py` created — FastAPI + background RabbitMQ consumer
- [x] **Spring Boot starts successfully on port 8080** 🎉
- [x] **Python AI Service runs on port 8000** 🎉
- [ ] End-to-end test: browser action → Java publishes → Python prints (skipped, manual test pending)
