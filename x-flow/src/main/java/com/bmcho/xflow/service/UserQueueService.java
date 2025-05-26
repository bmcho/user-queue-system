package com.bmcho.xflow.service;

import com.bmcho.xflow.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.time.Instant;
import java.util.function.Function;

@Log4j2
@Service
@RequiredArgsConstructor
public class UserQueueService {
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    Function<String, String> userQueueWaitKey = "users:queue:%s:wait"::formatted;
    Function<String, String> userQueueProceedKey = "users:queue:%s:proceed"::formatted;

    @Value("${scheduler.enabled}")
    private Boolean scheduling = false;


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

    @Scheduled(initialDelay = 5000, fixedDelay = 10000)
    public void scheduleAllowUser() {
        if (!scheduling) {
            log.info("passed scheduling...");
            return;
        }
        log.info("called scheduling...");

        var maxAllowUserCount = 3L;
        String USER_QUEUE_WAIT_KEY_FOR_SCAN = "users:queue:*:wait";
        reactiveRedisTemplate.scan(ScanOptions.scanOptions()
                .match(USER_QUEUE_WAIT_KEY_FOR_SCAN)
                .count(100)
                .build())
            .map(key -> key.split(":")[2])
            .flatMap(queue -> allowUser(queue, maxAllowUserCount).map(allowed -> Tuples.of(queue, allowed)))
            .doOnNext(tuple -> log.info("Tried %d and allowed %d members of %s queue".formatted(maxAllowUserCount, tuple.getT2(), tuple.getT1())))
            .subscribe();
    }

}