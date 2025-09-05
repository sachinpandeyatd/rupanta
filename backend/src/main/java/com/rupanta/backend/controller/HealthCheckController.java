package com.rupanta.backend.controller;

import com.rupanta.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthCheckController {

	private final UserRepository userRepository;

	@GetMapping
	public ResponseEntity<Map<String, String>> checkHealth(){
		try{
			long userCount = userRepository.count();
			Map<String, String> response = Map.of(
				"status", "UP",
				"db_connection", "OK",
				"users_found", String.valueOf(userCount)
			);
			return ResponseEntity.ok(response);
		}catch (Exception e){
			Map<String, String> response = Map.of(
				"status", "DOWN",
				"db_connection", "Error: " + e.getMessage()
			);
			return ResponseEntity.status(503).body(response);
		}
	}
}
