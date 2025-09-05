package com.rupanta.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3StorageService {

	private final S3Client s3Client;

	@Value("${aws.s3.bucket-name}")
	private String bucketName;

	public String uploadFile(String folder, MultipartFile file){
		String ogFileName = file.getOriginalFilename();
		String uniqueKey = folder + "/" + UUID.randomUUID().toString() + "-" + ogFileName;

		PutObjectRequest putObjectRequest = PutObjectRequest.builder()
				.bucket(bucketName)
				.key(uniqueKey)
				.contentType(file.getContentType())
				.build();

		try {
			s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
			log.info("Successfully uploaded file {} to S3 bucket {}", uniqueKey, bucketName);
			return uniqueKey;
		} catch (IOException e) {
			log.error("Error uploading file to S3: {}", e.getMessage());
			throw new RuntimeException("Failed to upload file to S3", e);
		}
	}
}
