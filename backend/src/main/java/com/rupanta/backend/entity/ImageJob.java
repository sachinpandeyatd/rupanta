package com.rupanta.backend.entity;

import com.rupanta.backend.enums.JobStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "image_jobs")
@Data
public class ImageJob {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = true)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private JobStatus status;

	@Column(name = "raw_file_key")
	private String rawFileKey;

	@Column(name = "processed_file_key")
	private String processedFileKey;

	@Column(name = "job_parameters", columnDefinition = "jsonb")
	private String jobParameters;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "completed_at")
	private Instant completedAt;
}
