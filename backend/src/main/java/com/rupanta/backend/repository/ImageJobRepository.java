package com.rupanta.backend.repository;

import com.rupanta.backend.entity.ImageJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ImageJobRepository extends JpaRepository<ImageJob, UUID> {
	//
}
