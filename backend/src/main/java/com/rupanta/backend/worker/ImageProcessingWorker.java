package com.rupanta.backend.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupanta.backend.dto.ImageProcessRequest;
import com.rupanta.backend.entity.ImageJob;
import com.rupanta.backend.enums.JobStatus;
import com.rupanta.backend.exception.CompressionException;
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
import java.util.Map;
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

	private static final int MAX_COMPRESSION_ATTEMPTS = 20;

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
			ImageProcessRequest.CompressionParams compression = params.getCompression();

			boolean needsProcessing = true;

			if(compression != null && compression.getMinSize() != null && compression.getMaxSize() != null){
				long minBytes = (long) (compression.getMinSize() * getMultiplier(compression.getUnit()));
				long maxBytes = (long) (compression.getMaxSize() * getMultiplier(compression.getUnit()));
				long currentSize = inputFile.length();

				if (currentSize >= minBytes && currentSize <= maxBytes) {
					log.info("File size ({}) is already within the target range [{}, {}]. Skipping processing.", currentSize, minBytes, maxBytes);
					outputFile = inputFile;
					needsProcessing = false;
				}
			}

			if(needsProcessing) {
				outputFile = Files.createTempFile("rupanta-out-", "." + outputExtension).toFile();
				List<String> command = buildFFmpegCommand(params, inputFile.getAbsolutePath(), outputFile.getAbsolutePath());

				if("jpg".equalsIgnoreCase(outputExtension) || "jpeg".equalsIgnoreCase(outputExtension)) {
					if (compression != null) {
						if (compression.getQuality() != null) {
							log.info("Compression: Quality mode to {}%", compression.getQuality());
							int ffmpegQuality = (int) Math.round(31 - (compression.getQuality() * 29.0 / 100.0));
							ffmpegQuality = Math.max(2, Math.min(31, ffmpegQuality));

							command.add("-q:v");
							command.add(String.valueOf(ffmpegQuality));

							executeCommand(command);
						} else if (compression.getMinSize() != null && compression.getMaxSize() != null && compression.getUnit() != null) {
							long minBytes = (long) (compression.getMinSize() * getMultiplier(compression.getUnit()));
							long maxBytes = (long) (compression.getMaxSize() * getMultiplier(compression.getUnit()));

							cleanup(outputFile);
							outputFile = findOptimalQuality(params, inputFile, minBytes, maxBytes);
						} else {
							command.add("-q:v");
							command.add("2");
							executeCommand(command);
						}
					} else {
						command.add("-q:v");
						command.add("2");
						executeCommand(command);
					}
				}else {
					executeCommand(command);
				}
			}

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

			if (inputFile != null && !inputFile.equals(outputFile)){
				cleanup(inputFile);
			}
			cleanup(outputFile);
		}
	}

	private Double getMultiplier(String unit) {
		if("MiB".equalsIgnoreCase(unit)) return 1024.0 * 1024.0;
		if("KiB".equalsIgnoreCase(unit)) return 1024.0;
		return 1.0;
	}

	private File findOptimalQuality(ImageProcessRequest params, File inputFile, long minBytes, long maxBytes) throws IOException, InterruptedException, CompressionException {
		int lowQ = 2, highQ = 31;
		File optimalFile = null;
		long sizeAtBestQuality = -1, sizeAtWorstQuality = -1;

		for (int i = 0; i < MAX_COMPRESSION_ATTEMPTS; i++) {
			int currentQ = (lowQ + highQ) / 2;

			if (currentQ == 0) currentQ = 1;

			File tempFile = runCompression(params, inputFile, currentQ);
			long currentSizeBytes = tempFile.length();

			if (i == 0) sizeAtBestQuality = runCompression(params, inputFile, 2).length();
			if (i == 0) sizeAtWorstQuality = runCompression(params, inputFile, 31).length();

			log.info("Attempt {}: Trying q={}, size={} bytes. Target: [{}, {}]", i + 1, currentQ, currentSizeBytes, minBytes, maxBytes);


			if (currentSizeBytes >= minBytes && currentSizeBytes <= maxBytes){
				log.info("SUCCESS: Found optimal quality q={} within target range.", currentQ);
				if (optimalFile != null) cleanup(optimalFile);
				return tempFile;
			} else if (currentSizeBytes < minBytes) {
				highQ = currentQ - 1;
				if (optimalFile != null) cleanup(optimalFile);
				optimalFile = tempFile;
			}else {
				lowQ = currentQ + 1;
				cleanup(tempFile);
			}

			if (lowQ > highQ) break;
		}

		if (optimalFile != null && optimalFile.length() <= maxBytes){
			log.warn("Could NOT find a perfect match. Using best effort result (under max size).");
			return optimalFile;
		}

		throw new CompressionException("Could not meet target size.", sizeAtBestQuality, sizeAtWorstQuality, minBytes, maxBytes);
	}

	private File runCompression(ImageProcessRequest params, File inputFile, int quality) throws IOException, InterruptedException {
		File tempFile = Files.createTempFile("rupanta-run-", ".jpg").toFile();
		List<String> command = buildFFmpegCommand(params, inputFile.getAbsolutePath(), tempFile.getAbsolutePath());
		command.add("-q:v");
		command.add(String.valueOf(quality));
		executeCommand(command);
		return tempFile;
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
//			command.add("-q:v");
//			command.add("2");
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

		log.info("Ready to execute FFmpeg command: {}", String.join(" ", command));
		try(var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))){
			String line;
			while ((line = reader.readLine()) != null){
				log.debug("FFmpeg output: {}", line);
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
