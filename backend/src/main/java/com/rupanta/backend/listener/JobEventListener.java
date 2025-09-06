package com.rupanta.backend.listener;

import com.rupanta.backend.worker.ImageProcessingWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobEventListener {

	private final ImageProcessingWorker imageProcessingWorker;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onJobCreated(UUID jobId){
		log.info("Transaction commited for the job: {}. Triggering async worker.", jobId);
		imageProcessingWorker.processImageJob(jobId);
	}
}
