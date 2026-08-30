package com.zkry.config;

import com.zkry.task.TaskQueues;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.TaskExecutorAdapter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "tripstar.tasks.transport", havingValue = "rabbit")
public class TaskRabbitConfiguration {

    private static final int[] RETRY_DELAYS_SECONDS = {30, 120, 300};

    @Bean
    public Declarables taskTopology() {
        DirectExchange taskExchange = new DirectExchange(TaskQueues.TASK_EXCHANGE, true, false);
        DirectExchange retryExchange = new DirectExchange(TaskQueues.RETRY_EXCHANGE, true, false);
        DirectExchange dlxExchange = new DirectExchange(TaskQueues.DLX_EXCHANGE, true, false);

        Queue plan = mainQueue(TaskQueues.PLAN_QUEUE);
        Queue modification = mainQueue(TaskQueues.MODIFICATION_QUEUE);
        Queue knowledge = mainQueue(TaskQueues.KNOWLEDGE_QUEUE);
        Queue dead = QueueBuilder.durable(TaskQueues.DLQ).build();

        List<Declarable> values = new ArrayList<>();
        values.addAll(List.of(taskExchange, retryExchange, dlxExchange, plan, modification, knowledge, dead));
        values.addAll(List.of(
            BindingBuilder.bind(plan).to(taskExchange).with(TaskQueues.PLAN_KEY),
            BindingBuilder.bind(modification).to(taskExchange).with(TaskQueues.MODIFICATION_KEY),
            BindingBuilder.bind(knowledge).to(taskExchange).with(TaskQueues.KNOWLEDGE_KEY),
            BindingBuilder.bind(dead).to(dlxExchange).with(TaskQueues.DLQ_KEY)
        ));
        addRetryQueues(values, retryExchange, "plan", TaskQueues.PLAN_KEY);
        addRetryQueues(values, retryExchange, "modification", TaskQueues.MODIFICATION_KEY);
        addRetryQueues(values, retryExchange, "knowledge", TaskQueues.KNOWLEDGE_KEY);
        return new Declarables(values);
    }

    @Bean("planTaskRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory planTaskRabbitListenerContainerFactory(
        ConnectionFactory connectionFactory,
        @Qualifier("taskVirtualThreadExecutor") ExecutorService taskVirtualThreadExecutor,
        @Value("${tripstar.tasks.consumer.plan.concurrency:2}") int concurrency,
        @Value("${tripstar.tasks.consumer.plan.max-concurrency:2}") int maxConcurrency,
        @Value("${tripstar.tasks.consumer.plan.prefetch:1}") int prefetch
    ) {
        return listenerFactory(
            connectionFactory,
            taskVirtualThreadExecutor,
            concurrency,
            maxConcurrency,
            prefetch
        );
    }

    @Bean("modificationTaskRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory modificationTaskRabbitListenerContainerFactory(
        ConnectionFactory connectionFactory,
        @Qualifier("taskVirtualThreadExecutor") ExecutorService taskVirtualThreadExecutor,
        @Value("${tripstar.tasks.consumer.modification.concurrency:1}") int concurrency,
        @Value("${tripstar.tasks.consumer.modification.max-concurrency:1}") int maxConcurrency,
        @Value("${tripstar.tasks.consumer.modification.prefetch:1}") int prefetch
    ) {
        return listenerFactory(
            connectionFactory,
            taskVirtualThreadExecutor,
            concurrency,
            maxConcurrency,
            prefetch
        );
    }

    @Bean("knowledgeTaskRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory knowledgeTaskRabbitListenerContainerFactory(
        ConnectionFactory connectionFactory,
        @Qualifier("taskVirtualThreadExecutor") ExecutorService taskVirtualThreadExecutor,
        @Value("${tripstar.tasks.consumer.knowledge.concurrency:1}") int concurrency,
        @Value("${tripstar.tasks.consumer.knowledge.max-concurrency:1}") int maxConcurrency,
        @Value("${tripstar.tasks.consumer.knowledge.prefetch:1}") int prefetch
    ) {
        return listenerFactory(
            connectionFactory,
            taskVirtualThreadExecutor,
            concurrency,
            maxConcurrency,
            prefetch
        );
    }

    private SimpleRabbitListenerContainerFactory listenerFactory(
        ConnectionFactory connectionFactory,
        ExecutorService taskVirtualThreadExecutor,
        int concurrency,
        int maxConcurrency,
        int prefetch
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(Math.max(1, concurrency));
        factory.setMaxConcurrentConsumers(Math.max(concurrency, maxConcurrency));
        factory.setPrefetchCount(Math.max(1, prefetch));
        factory.setTaskExecutor(new TaskExecutorAdapter(taskVirtualThreadExecutor));
        return factory;
    }

    private Queue mainQueue(String name) {
        return QueueBuilder.durable(name)
            .deadLetterExchange(TaskQueues.DLX_EXCHANGE)
            .deadLetterRoutingKey(TaskQueues.DLQ_KEY)
            .build();
    }

    private void addRetryQueues(
        List<Declarable> values,
        DirectExchange retryExchange,
        String family,
        String targetRoutingKey
    ) {
        for (int delay : RETRY_DELAYS_SECONDS) {
            String queueName = "tripstar.task.retry." + family + "." + delay;
            String routingKey = "retry." + family + "." + delay;
            Queue queue = QueueBuilder.durable(queueName)
                .withArguments(Map.of(
                    "x-message-ttl", delay * 1000,
                    "x-dead-letter-exchange", TaskQueues.TASK_EXCHANGE,
                    "x-dead-letter-routing-key", targetRoutingKey
                ))
                .build();
            Binding binding = BindingBuilder.bind(queue).to(retryExchange).with(routingKey);
            values.add(queue);
            values.add(binding);
        }
    }
}
