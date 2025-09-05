package com.rupanta.backend.dto;

import com.rupanta.backend.enums.JobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
public class JobStatusResponse {
	private UUID jobId;
	private JobStatus status;
	private String downloadUrl;
}
