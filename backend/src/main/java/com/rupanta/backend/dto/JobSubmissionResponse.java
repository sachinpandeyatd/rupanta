package com.rupanta.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class JobSubmissionResponse {
	private UUID jobId;
}
