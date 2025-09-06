package com.rupanta.backend.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupanta.backend.dto.ImageProcessRequest;
import com.rupanta.backend.entity.ImageJob;
import com.rupanta.backend.enums.JobStatus;
import com.rupanta.backend.repository.ImageJobRepository;
import com.rupanta.backend.service.S3StorageService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImageProcessingWorker {

	private final ImageJobRepository imageJobRepository;
	private final S3StorageService s3StorageService;
	private final ObjectMapper objectMapper;

	private static final String PROCESSED_FILES_FOLDER = "processed-files";

	@Async
	@Transactional
	public void processImageJob(UUID jobId){
		log.info("STARTING async processing for job: {}", jobId);

		ImageJob job = imageJobRepository.findById(jobId)
				.orElseThrow(() -> new EntityNotFoundException("Job not found: " + jobId));
		job.setStatus(JobStatus.PROCESSING);
		imageJobRepository.save(job);

		File inputFile = null;
		File outputFile = null;

		try{
			inputFile = s3StorageService.downloadFile(job.getRawFileKey());
			ImageProcessRequest params = objectMapper.readValue(job.getJobParameters(), ImageProcessRequest.class);

			String outputExtension = params.getOutputFormat() != null ? params.getOutputFormat().toLowerCase() : "jpg";
			outputFile = Files.createTempFile("rupanta-out-", "." + outputExtension).toFile();

			List<String> command = buildFFmpegCommand(params, inputFile.getAbsolutePath(), outputFile.getAbsolutePath());
			executeCommand(command);

			String processedFileKey = s3StorageService.uploadFile(PROCESSED_FILES_FOLDER, outputFile, params.getOutputFormat());
			job.setProcessedFileKey(processedFileKey);

			job.setStatus(JobStatus.COMPLETED);
			job.setCompletedAt(Instant.now());
			log.info("SUCCESSFULLY processed job: {}", jobId);
		} catch (Exception e) {
			log.error("FAILED to process job: {}. Reason: {}", jobId, e.getMessage());
			job.setStatus(JobStatus.FAILED);
		}finally {
			imageJobRepository.save(job);
//			cleanup(inputFile);
//			cleanup(outputFile);
		}
	}

	private List<String> buildFFmpegCommand(ImageProcessRequest params, String inputPath, String outputPath) {
		List<String> command = new ArrayList<>();
		command.add("ffmpeg");
		command.add("-i");
		command.add(inputPath);
		command.add("-y"); //overwrite if exists
		command.add("-map_metadata");
		command.add("0");

		List<String> videoFilters = new ArrayList<>();

		if (params.getCrop() != null){
			ImageProcessRequest.CropParams crop = params.getCrop();
			videoFilters.add(String.format("crop=%d:%d:%d:%d", crop.getWidth(), crop.getHeight(), crop.getX(), crop.getY()));
		}

		if (params.getResize() != null){
			ImageProcessRequest.ResizeParams resize = params.getResize();
			log.info(resize.getWidth() + " jds " + resize.getHeight());
			videoFilters.add(String.format("scale=%d:%d", resize.getWidth(), resize.getHeight()));
		}

		if (!videoFilters.isEmpty()){
			command.add("-vf");
			command.add(String.join(",", videoFilters));
		}

		command.add("-frames:v");
		command.add("1");
		command.add("-update");
		command.add("1");

		if("jpg".equalsIgnoreCase(params.getOutputFormat()) || "jpeg".equalsIgnoreCase(params.getOutputFormat())){
			command.add("-c:v");
			command.add("mjpeg");
			command.add("-q:v");
			command.add("2");
		}

		if(params.getDpi() != null){
			command.add("-dpi");
			command.add(String.valueOf(params.getDpi()));
		}

		command.add(outputPath);
		log.info("Built FFmpeg command: {}", String.join(" ", command));
		return command;
	}

	private void executeCommand(List<String> command) throws IOException, InterruptedException {
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.redirectErrorStream(true);
		Process process = processBuilder.start();

		log.info("Ready to execute FFmpeg command: {}", command);
		try(var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))){
			String line;
			while ((line = reader.readLine()) != null){
				log.info("FFmpeg output: {}", line);
			}
		}

		boolean finished = process.waitFor(2, TimeUnit.MINUTES);
		if (!finished){
			process.destroy();
			throw new RuntimeException("FFmpeg process timed out.");
		}

		if (process.exitValue() != 0){
			throw new RuntimeException("FFmpeg process failed with exit code " + process.exitValue());
		}

	}

	private void cleanup(File file) {
		if (file != null && file.exists()){
			if (file.delete()){
				log.info("Cleaned up temporary file: {}", file.getAbsolutePath());
			}else {
				log.warn("Could NOT delete temporary file: {}", file.getAbsolutePath());
			}
		}
	}
}
