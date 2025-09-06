package com.rupanta.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3StorageService {

	private final S3Client s3Client;

	private final S3Presigner s3Presigner;

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

	public File downloadFile(String key) throws IOException{
		GetObjectRequest getObjectRequest = GetObjectRequest.builder()
				.bucket(bucketName)
				.key(key)
				.build();

		ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getObjectRequest);
		byte[] data = objectBytes.asByteArray();

		File tempFile = File.createTempFile("rupanta-", ".tmp");
		try (OutputStream os = new FileOutputStream(tempFile)){
			os.write(data);
		}
		log.info("Successfully downloaded file {} from S3 to temporary path {}", key, tempFile.getAbsolutePath());
		return tempFile;
	}

	public String generatedPresignedUrl(String key){
		GetObjectRequest getObjectRequest = GetObjectRequest.builder()
				.bucket(bucketName)
				.key(key)
				.build();

		GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
				.signatureDuration(Duration.ofMinutes(15))
				.getObjectRequest(getObjectRequest)
				.build();

		PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
		log.info("Generated presigned URL for key: {}", key);
		return presignedRequest.url().toString();
	}

	public String uploadFile(String folder, File file, String outputFormat) {
		String ogFileName = file.getName();
		String uniqueKey = folder + "/" + UUID.randomUUID().toString() + "-" + FilenameUtils.getBaseName(ogFileName) + "." + outputFormat;

		String contentType = "image/" + ("png".equalsIgnoreCase(outputFormat) ? "png" : "jpeg");

		PutObjectRequest putObjectRequest = PutObjectRequest.builder()
				.bucket(bucketName)
				.key(uniqueKey)
				.contentType(contentType)
				.build();

		try{
			s3Client.putObject(putObjectRequest, RequestBody.fromFile(file));
			log.info("Successfully uploaded processed file {} to S3 bucket {}", uniqueKey, bucketName);
			return uniqueKey;
		}catch (S3Exception e){
			log.error("Error uploading processed file to S3: {} ", e.getMessage());
			throw new RuntimeException("Failed to upload processed file to S3", e);
		}
	}
}
