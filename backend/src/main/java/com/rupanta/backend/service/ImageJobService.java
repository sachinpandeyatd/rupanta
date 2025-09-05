package com.rupanta.backend.service;

import com.rupanta.backend.dto.ImageProcessRequest;
import com.rupanta.backend.dto.JobStatusResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface ImageJobService {
	UUID createAndSubmitJob(MultipartFile file, ImageProcessRequest params);

	JobStatusResponse getJobStatus(UUID jobId);
}
