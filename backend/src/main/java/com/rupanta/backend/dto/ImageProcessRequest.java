package com.rupanta.backend.dto;

import lombok.Data;

@Data
public class ImageProcessRequest {
	private String outputFormat;
	private Integer targetSizeKb;
	private Integer dpi;

	private ResizeParams resize;
	private CropParams crop;

	@Data
	public static class ResizeParams{
		private Integer width;
		private Integer height;

		private String unit;
		private boolean lockAspectRatio;
	}

	@Data
	public static class CropParams{
		private int x;
		private int y;
		private int width;
		private int height;
	}
}
