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
import org.apache.commons.io.FilenameUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
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
	private static final int MAX_COMPRESSION_ATTEMPTS = 8; // GM quality is 0-100, binary search is fast.

	@Async
	@Transactional
	public void processImageJob(UUID jobId) {
		log.info("STARTING GM processing for job: {}", jobId);

		ImageJob job = imageJobRepository.findById(jobId).orElseThrow(() -> new EntityNotFoundException("Job not found: " + jobId));
		job.setStatus(JobStatus.PROCESSING);
		imageJobRepository.save(job);

		File inputFile = null;
		File outputFile = null;

		try {
			inputFile = s3StorageService.downloadFile(job.getRawFileKey());
			ImageProcessRequest params = objectMapper.readValue(job.getJobParameters(), ImageProcessRequest.class);
			String outputExtension = params.getOutputFormat() != null ? params.getOutputFormat().toLowerCase() : "jpg";
			ImageProcessRequest.CompressionParams compression = params.getCompression();

			boolean needsProcessing = true;

			// Check if file already meets size requirements
			if (compression != null && compression.getMinSize() != null && compression.getMaxSize() != null) {
				long minBytes = (long) (compression.getMinSize() * getMultiplier(compression.getUnit()));
				long maxBytes = (long) (compression.getMaxSize() * getMultiplier(compression.getUnit()));
				long currentSize = inputFile.length();

				if (currentSize >= minBytes && currentSize <= maxBytes) {
					log.info("File size ({}) is already within the target range [{}, {}]. Skipping processing.", currentSize, minBytes, maxBytes);
					outputFile = inputFile;
					needsProcessing = false;
				}
			}

			if (needsProcessing) {
				if (compression != null && "jpg".equalsIgnoreCase(outputExtension) && compression.getMinSize() != null && compression.getMaxSize() != null) {
					// Target Size Mode: Binary search for optimal quality
					long minBytes = (long) (compression.getMinSize() * getMultiplier(compression.getUnit()));
					long maxBytes = (long) (compression.getMaxSize() * getMultiplier(compression.getUnit()));
					outputFile = findOptimalQuality(params, inputFile, minBytes, maxBytes);
				} else {
					// Standard Mode: Resize, crop, quality %, or format change
					outputFile = Files.createTempFile("rupanta-out-", "." + outputExtension).toFile();
					List<String> command = buildGraphicsMagickCommand(params, inputFile.getAbsolutePath(), outputFile.getAbsolutePath());
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
		} finally {
			imageJobRepository.save(job);
			if (inputFile != null && !inputFile.equals(outputFile)) {
//				cleanup(inputFile);
			}
//			cleanup(outputFile);
		}
	}

	private List<String> buildGraphicsMagickCommand(ImageProcessRequest params, String inputPath, String outputPath) {
		List<String> command = new ArrayList<>();
		command.add("gm");
		command.add("convert");

		command.add(inputPath);

		if (params.getDpi() != null && params.getDpi() > 0) {
			command.add("-density");
			command.add(params.getDpi() + "x" + params.getDpi());
		}

		if (params.getResize() != null) {
			ImageProcessRequest.ResizeParams resize = params.getResize();
			if (resize.getWidth() > 0 && resize.getHeight() > 0) {
				command.add("-resize");
				command.add(resize.getWidth() + "x" + resize.getHeight() + "!"); // '!' ignores aspect ratio
			}
		}

		if (params.getCrop() != null) {
			ImageProcessRequest.CropParams crop = params.getCrop();
			command.add("-crop");
			command.add(String.format("%dx%d+%d+%d", crop.getWidth(), crop.getHeight(), crop.getX(), crop.getY()));
		}

		ImageProcessRequest.CompressionParams compression = params.getCompression();

		if (compression != null && compression.getQuality() != null) {
			command.add("-quality");
			command.add(String.valueOf(compression.getQuality()));
		} else if (compression == null || (compression.getMinSize() == null && compression.getMaxSize() == null)) {
			command.add("-quality");
			command.add("100");

			command.add("-sampling-factor");
			command.add("1x1,1x1,1x1");
		}


		command.add(outputPath);
		return command;
	}

	private File findOptimalQuality(ImageProcessRequest params, File inputFile, long minBytes, long maxBytes) throws IOException, InterruptedException, CompressionException {
		int lowQ = 0, highQ = 100, optimalQ = -1;
		File optimalFile = null;

		File bestQualityFile = runGmCompression(params, inputFile, 100);
		long sizeAtBestQuality = bestQualityFile.length();
		if (sizeAtBestQuality < minBytes) {
			cleanup(bestQualityFile);
			throw new CompressionException("Image is too simple.", sizeAtBestQuality, -1, minBytes, maxBytes);
		}

		File worstQualityFile = runGmCompression(params, inputFile, 0);
		long sizeAtWorstQuality = worstQualityFile.length();
		if (sizeAtWorstQuality > maxBytes) {
			cleanup(bestQualityFile);
			cleanup(worstQualityFile);
			throw new CompressionException("Image is too complex.", sizeAtBestQuality, sizeAtWorstQuality, minBytes, maxBytes);
		}

		cleanup(bestQualityFile);
		cleanup(worstQualityFile);


		for (int i = 0; i < MAX_COMPRESSION_ATTEMPTS; i++) {
			int currentQ = (lowQ + highQ) / 2;
			File tempFile = runGmCompression(params, inputFile, currentQ);
			long currentSizeBytes = tempFile.length();
			log.info("Attempt {}: Trying quality q={}, size={} bytes. Target: [{}, {}]", i + 1, currentQ, currentSizeBytes, minBytes, maxBytes);

			if (currentSizeBytes >= minBytes && currentSizeBytes <= maxBytes) {
				log.info("SUCCESS: Found optimal quality q={}.", currentQ);
				if (optimalFile != null) cleanup(optimalFile);
				return tempFile;
			} else if (currentSizeBytes < minBytes) {
				lowQ = currentQ + 1;
				if (optimalFile != null) cleanup(optimalFile);
				optimalFile = tempFile;
			} else { // currentSizeBytes > maxBytes
				highQ = currentQ - 1;
				cleanup(tempFile);
			}
			if (lowQ > highQ) break;
		}

		if (optimalFile != null && optimalFile.length() <= maxBytes) {
			log.warn("Could not find perfect match. Using best effort (under max size).");
			return optimalFile;
		}

		throw new CompressionException("Could not meet target size.", sizeAtBestQuality, sizeAtWorstQuality, minBytes, maxBytes);
	}

	private File runGmCompression(ImageProcessRequest params, File inputFile, int quality) throws IOException, InterruptedException {
		File tempFile = Files.createTempFile("rupanta-gm-run-", ".jpg").toFile();
		List<String> command = buildGraphicsMagickCommand(params, inputFile.getAbsolutePath(), tempFile.getAbsolutePath());

		// Insert the quality flag before the output path
		int outputPathIndex = command.size() - 1;
		command.add(outputPathIndex, "-quality");
		command.add(outputPathIndex + 1, String.valueOf(quality));

		executeCommand(command);
		return tempFile;
	}

	private void executeCommand(List<String> command) throws IOException, InterruptedException {
		log.info("GM command to be executed: {}", String.join(" ", command));

		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.redirectErrorStream(true);
		Process process = processBuilder.start();

		try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				log.info("GM output: {}", line);
			}
		}

		boolean finished = process.waitFor(2, TimeUnit.MINUTES);
		if (!finished) {
			process.destroyForcibly();
			throw new RuntimeException("GraphicsMagick process timed out.");
		}

		if (process.exitValue() != 0) {
			throw new RuntimeException("GraphicsMagick process failed with exit code " + process.exitValue());
		}
	}

	private double getMultiplier(String unit) {
		if (unit == null) return 1024.0; // Default to KiB if unit is not specified
		if ("MiB".equalsIgnoreCase(unit)) return 1024.0 * 1024.0;
		return 1024.0; // KiB is the fallback
	}

	private void cleanup(File file) {
		if (file != null && file.exists()) {
			if (!file.delete()) {
				log.warn("Could not delete temporary file: {}", file.getAbsolutePath());
			}
		}
	}
}