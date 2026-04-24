package com.skillbridge.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "skillbridge-ai-exchange";
    public static final String QUEUE_NAME = "ai.analysis.queue";
    public static final String ROUTING_KEY = "ai.analysis.route";

    @Bean
    public Queue aiAnalysisQueue() {
        // Creates a durable queue so messages aren't lost if the server restarts
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    @Bean
    public Binding binding(Queue aiAnalysisQueue, DirectExchange aiExchange) {
        // Binds the queue to the exchange using the routing key
        return BindingBuilder.bind(aiAnalysisQueue).to(aiExchange).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        // Automatically converts Java objects to JSON payloads when sending to RabbitMQ
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
