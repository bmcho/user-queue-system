package com.bmcho.xflow.service;

import com.bmcho.xflow.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class UserQueueService {
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    Function<String, String> userQueueWaitKey = "users:queue:%s:wait"::formatted;
    Function<String, String> userQueueProceedKey = "users:queue:%s:proceed"::formatted;

    public Mono<Long> registerWaitQueue(final String queue, final Long userId) {
        var unixTimestamp = Instant.now().getEpochSecond();
        return reactiveRedisTemplate.opsForZSet().add(userQueueWaitKey.apply(queue), userId.toString(), unixTimestamp)
            .filter(i -> i)
            .switchIfEmpty(Mono.error(ErrorCode.QUEUE_ALREADY_REGISTERED_USER.build()))
            .flatMap(i -> reactiveRedisTemplate.opsForZSet().rank(userQueueWaitKey.apply(queue), userId.toString()))
            .map(i -> i >= 0 ? i + 1 : i);
    }


    public Mono<Long> allowUser(final String queue, final Long count) {
        return reactiveRedisTemplate.opsForZSet().popMin(userQueueWaitKey.apply(queue), count)
            .flatMap(m -> reactiveRedisTemplate.opsForZSet().add(userQueueProceedKey.apply(queue), m.getValue(), Instant.now().getEpochSecond()))
            .count();
    }

    public Mono<Boolean> isAllowed(final String queue, final Long userId) {
        return reactiveRedisTemplate.opsForZSet().rank(userQueueProceedKey.apply(queue), userId.toString())
            .defaultIfEmpty(-1L)
            .map(rank -> rank >= 0);
    }

    public Mono<Long> getRank(final String queue, final Long userId) {
        return reactiveRedisTemplate.opsForZSet().rank(userQueueWaitKey.apply(queue), userId.toString())
            .defaultIfEmpty(-1L)
            .map(rank -> rank >= 0 ? rank + 1: rank);
    }

}