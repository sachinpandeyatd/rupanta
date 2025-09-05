package com.rupanta.backend.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupanta.backend.dto.ImageProcessRequest;
import com.rupanta.backend.dto.JobStatusResponse;
import com.rupanta.backend.dto.JobSubmissionResponse;
import com.rupanta.backend.service.ImageJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class ImageProcessingController {

	private final ImageJobService imageJobService;
	private final ObjectMapper objectMapper;

	@PostMapping(consumes = {"multipart/form-data"})
	public ResponseEntity<JobSubmissionResponse> processImage(
			@RequestPart("image") MultipartFile image,
			@RequestPart("params") String paramsJson){

		if (image.isEmpty()){
			return ResponseEntity.badRequest().build();
		}

		try{
			ImageProcessRequest params = objectMapper.readValue(paramsJson, ImageProcessRequest.class);
			UUID jobId = imageJobService.createAndSubmitJob(image, params);
			JobSubmissionResponse response = new JobSubmissionResponse(jobId);

			return new ResponseEntity<>(response, HttpStatus.ACCEPTED);
		} catch (JsonProcessingException e) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
	}

	@GetMapping("/{jobId}")
	public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable UUID jobId){
		JobStatusResponse statusResponse = imageJobService.getJobStatus(jobId);
		return ResponseEntity.ok(statusResponse);
	}
}
