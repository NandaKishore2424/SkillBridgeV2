import pika
import os
import json
import threading

from fastapi import FastAPI
from dotenv import load_dotenv

load_dotenv()

app = FastAPI()

# CloudAMQP URL
AMQP_URL = os.getenv("AMQP_URL", "amqps://gvcxbqgm:Nm-4KJfWbJ73S0vHX-RZgWKcdDD0rsLW@puffin.rmq2.cloudamqp.com/gvcxbqgm")

def callback(ch, method, properties, body):
    # This runs whenever Java drops a message into the queue!
    payload = json.loads(body)
    print(f" [x] A Message arrived from Java!: {payload}")
    
def start_rabbitmq_consumer():
    parameters = pika.URLParameters(AMQP_URL)
    connection = pika.BlockingConnection(parameters)
    channel = connection.channel()

    # Ensure the exact same queue name that Java created exists
    channel.queue_declare(queue='ai.analysis.queue', durable=True)

    print(' [*] Python FastAPI is connected to CloudAMQP. Waiting for messages from Java...')
    channel.basic_consume(queue='ai.analysis.queue', on_message_callback=callback, auto_ack=True)
    channel.start_consuming()

# Start RabbitMQ listening in a background thread so it doesn't block the FastAPI web server
threading.Thread(target=start_rabbitmq_consumer, daemon=True).start()

@app.get("/")
def read_root():
    return {"status": "SkillBridge AI Engine is running!"}
