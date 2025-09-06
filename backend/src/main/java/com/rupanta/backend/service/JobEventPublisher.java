package com.rupanta.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobEventPublisher {

	private final ApplicationEventPublisher applicationEventPublisher;

	public void publishJobCreatedEvent(final UUID jobId){
		log.info("Publishing job created event for jobId: {}", jobId);
		applicationEventPublisher.publishEvent(jobId);
	}
}
