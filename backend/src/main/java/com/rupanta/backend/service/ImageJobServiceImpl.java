package com.rupanta.backend.service;

import com.rupanta.backend.dto.ImageProcessRequest;
import com.rupanta.backend.dto.JobStatusResponse;
import com.rupanta.backend.enums.JobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageJobServiceImpl implements ImageJobService{
	@Override
	public UUID createAndSubmitJob(MultipartFile file, ImageProcessRequest params) {
		System.out.println("Inside createAndSubmitJob.");
		return UUID.randomUUID();
	}

	@Override
	public JobStatusResponse getJobStatus(UUID jobId) {
		System.out.println("Inside getJobStatus. Job ID - " + jobId);
		return JobStatusResponse.builder()
				.jobId(jobId)
				.status(JobStatus.PENDING)
				.build();
	}
}
