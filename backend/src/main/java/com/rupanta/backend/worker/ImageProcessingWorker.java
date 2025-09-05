package com.rupanta.backend.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ImageProcessingWorker {
	@Async
	public void processImageJob(UUID jobId){
		System.out.println("STARTING");

		try{
			Thread.sleep(10000);
		}catch (InterruptedException e){
			Thread.currentThread().interrupt();
		}
		System.out.println("FINISHED. Job ID - " + jobId);
	}
}
