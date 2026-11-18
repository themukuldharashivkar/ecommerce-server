package com.bazaarly.exception;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of every error response: a human-readable message, the request it relates to ("uri=/api/..."),
 * when it happened and, for validation errors only, the message for each invalid field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorDetails {

	private String error;
	private String details;
	private LocalDateTime timestamp;
	private Map<String, String> fieldErrors;

	public ErrorDetails() {
	}

	public ErrorDetails(String error, String details, LocalDateTime timestamp) {
		this.error = error;
		this.details = details;
		this.timestamp = timestamp;
	}

	public ErrorDetails(String error, String details, LocalDateTime timestamp, Map<String, String> fieldErrors) {
		this(error, details, timestamp);
		this.fieldErrors = fieldErrors;
	}

	public String getError() { return error; }
	public void setError(String error) { this.error = error; }
	public String getDetails() { return details; }
	public void setDetails(String details) { this.details = details; }
	public LocalDateTime getTimestamp() { return timestamp; }
	public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
	public Map<String, String> getFieldErrors() { return fieldErrors; }
	public void setFieldErrors(Map<String, String> fieldErrors) { this.fieldErrors = fieldErrors; }
}
