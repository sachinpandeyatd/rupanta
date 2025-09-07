package com.rupanta.backend.exception;

public class CompressionException extends Exception {
	public CompressionException(String message, long bestQuality, long worstQuality, long minTarget, long maxTarget) {
		super(compressionErrorMessage(message, bestQuality, worstQuality, minTarget, maxTarget));
	}

	private static String compressionErrorMessage(String baseMessage, long bestSize, long worstSize, long minTarget, long maxTarget) {
		if(bestSize < minTarget){
			return String.format("Image is too simple to meet the minimum size. The highest quality version is only %.2f Kib.", bestSize / 1024.0);
		}

		if (worstSize > maxTarget){
			return String.format("Image is too complex to meet the maximum size. Even the lowest quality version is %.2f KiB.", worstSize / 1024.0);
		}

		return baseMessage;
	}
}
