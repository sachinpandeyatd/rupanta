package com.rupanta.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupanta.backend.dto.ImageProcessRequest;
import com.rupanta.backend.dto.JobStatusResponse;
import com.rupanta.backend.entity.ImageJob;
import com.rupanta.backend.entity.User;
import com.rupanta.backend.enums.JobStatus;
import com.rupanta.backend.repository.ImageJobRepository;
import com.rupanta.backend.repository.UserRepository;
import com.rupanta.backend.worker.ImageProcessingWorker;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageJobServiceImpl implements ImageJobService{

	private final ImageJobRepository imageJobRepository;
	private final UserRepository userRepository;
	private final S3StorageService s3StorageService;
	private final ImageProcessingWorker imageProcessingWorker;
	private final ObjectMapper objectMapper;

	private static final String RAW_UPLOADS_FOLDER = "raw_uploads";

	@Override
	@Transactional
	public UUID createAndSubmitJob(MultipartFile file, ImageProcessRequest params) {
		String rawFileKey = s3StorageService.uploadFile(RAW_UPLOADS_FOLDER, file);
		String paramsJson;

		try {
			paramsJson = objectMapper.writeValueAsString(params);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Could not serialize job parameters", e);
		}

		ImageJob newJob = new ImageJob();
		newJob.setUser(getOrCreateAnonymousUser());
		newJob.setRawFileKey(rawFileKey);
		newJob.setJobParameters(paramsJson);
		newJob.setStatus(JobStatus.PENDING);

		ImageJob savedJob = imageJobRepository.save(newJob);
		imageProcessingWorker.processImageJob(savedJob.getId());

		return savedJob.getId();
	}

	@Override
	public JobStatusResponse getJobStatus(UUID jobId) {
		ImageJob job = imageJobRepository.findById(jobId)
				.orElseThrow(() -> new EntityNotFoundException("Job not found with ID: " + jobId));

		return JobStatusResponse.builder()
				.jobId(jobId)
				.status(job.getStatus())
				.downloadUrl(null)
				.build();
	}

	private User getOrCreateAnonymousUser(){
		return userRepository.findByEmail("anonymous@rupanta.com").orElseGet(() -> {
			User anonymousUser = new User();
			anonymousUser.setEmail("anonymous@rupanta.com");

			anonymousUser.setPasswordHash("N/A");
			return userRepository.save(anonymousUser);
		});
	}
}
