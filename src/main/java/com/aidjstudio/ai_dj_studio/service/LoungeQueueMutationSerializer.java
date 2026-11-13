package com.aidjstudio.ai_dj_studio.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Component
public class LoungeQueueMutationSerializer {

    private static class Task<T> {
        private final Supplier<Mono<T>> supplier;
        private final Sinks.One<T> sink;

        public Task(Supplier<Mono<T>> supplier, Sinks.One<T> sink) {
            this.supplier = supplier;
            this.sink = sink;
        }
    }

    private static class LoungePipeline {
        private final Sinks.Many<Task<?>> taskSink = Sinks.many().unicast().onBackpressureBuffer();

        public LoungePipeline() {
            taskSink.asFlux()
                    .concatMap(this::processTask)
                    .subscribe(
                            null,
                            error -> log.error("Lounge mutation pipeline terminated unexpectedly", error)
                    );
        }

        @SuppressWarnings("unchecked")
        private <T> Mono<Void> processTask(Task<T> task) {
            return Mono.defer(task.supplier)
                    .doOnSuccess(val -> {
                        if (val != null) {
                            task.sink.tryEmitValue(val);
                        } else {
                            task.sink.tryEmitEmpty();
                        }
                    })
                    .onErrorResume(ex -> {
                        task.sink.tryEmitError(ex);
                        return Mono.empty();
                    })
                    .then();
        }

        public synchronized <T> Mono<T> submit(Supplier<Mono<T>> supplier) {
            Sinks.One<T> sink = Sinks.one();
            Task<T> task = new Task<>(supplier, sink);
            taskSink.emitNext(task, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(500)));
            return sink.asMono();
        }
    }

    private final Map<Long, LoungePipeline> pipelines = new ConcurrentHashMap<>();

    public <T> Mono<T> executeSerialized(Long loungeId, Supplier<Mono<T>> supplier) {
        LoungePipeline pipeline = pipelines.computeIfAbsent(loungeId, id -> new LoungePipeline());
        return pipeline.submit(supplier);
    }

    public <T> Flux<T> executeSerializedFlux(Long loungeId, Supplier<Flux<T>> supplier) {
        return executeSerialized(loungeId, () -> supplier.get().collectList())
                .flatMapMany(Flux::fromIterable);
    }
}
