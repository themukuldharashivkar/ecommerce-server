package com.bazaarly.exception;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.validation.ConstraintViolationException;

/**
 * Turns every exception into the same JSON body ({@link ErrorDetails}) with a fitting status:
 * 400 invalid input, 401 bad credentials, 404 unknown resource or route, 405/415 wrong method or
 * content type, 500 anything unexpected. 500s never echo the exception message (it can contain
 * internals); the full error is logged instead.
 */
@ControllerAdvice
public class GlobleException {

	private static final Logger log = LoggerFactory.getLogger(GlobleException.class);

	private static ResponseEntity<ErrorDetails> error(HttpStatus status, String message, WebRequest req) {
		return new ResponseEntity<>(new ErrorDetails(message, req.getDescription(false), LocalDateTime.now()), status);
	}

	// ---------- 404: unknown resources (also used for other users' orders, so ids cannot be probed) ----------

	@ExceptionHandler({ ProductNotFoundException.class, OrderNotFoundException.class, CartItemNotFoundException.class })
	public ResponseEntity<ErrorDetails> notFound(Exception e, WebRequest req) {
		return error(HttpStatus.NOT_FOUND, e.getMessage(), req);
	}

	@ExceptionHandler(NoHandlerFoundException.class)
	public ResponseEntity<ErrorDetails> noRoute(NoHandlerFoundException e, WebRequest req) {
		return error(HttpStatus.NOT_FOUND, "Endpoint not found", req);
	}

	// ---------- 400: business rules and invalid input ----------

	@ExceptionHandler({ UserException.class, ProductException.class, CartItemException.class, OrderException.class })
	public ResponseEntity<ErrorDetails> businessRule(Exception e, WebRequest req) {
		return error(HttpStatus.BAD_REQUEST, e.getMessage(), req);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorDetails> invalidBody(MethodArgumentNotValidException e, WebRequest req) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
			fields.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
		}
		e.getBindingResult().getGlobalErrors().forEach(g -> fields.putIfAbsent(g.getObjectName(), g.getDefaultMessage()));
		return new ResponseEntity<>(new ErrorDetails("Validation failed", req.getDescription(false), LocalDateTime.now(), fields),
				HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorDetails> invalidParameter(ConstraintViolationException e, WebRequest req) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getConstraintViolations().forEach(v -> {
			String path = v.getPropertyPath().toString();
			fields.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), v.getMessage());
		});
		return new ResponseEntity<>(new ErrorDetails("Validation failed", req.getDescription(false), LocalDateTime.now(), fields),
				HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorDetails> unreadableBody(HttpMessageNotReadableException e, WebRequest req) {
		return error(HttpStatus.BAD_REQUEST, "Malformed or missing request body", req);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorDetails> missingParameter(MissingServletRequestParameterException e, WebRequest req) {
		return error(HttpStatus.BAD_REQUEST, "Missing required parameter '" + e.getParameterName() + "'", req);
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ErrorDetails> missingHeader(MissingRequestHeaderException e, WebRequest req) {
		return error(HttpStatus.BAD_REQUEST, "Missing required header '" + e.getHeaderName() + "'", req);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorDetails> wrongType(MethodArgumentTypeMismatchException e, WebRequest req) {
		return error(HttpStatus.BAD_REQUEST, "Invalid value for '" + e.getName() + "'", req);
	}

	// ---------- 401 / 405 / 415 ----------

	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<ErrorDetails> badCredentials(BadCredentialsException e, WebRequest req) {
		return error(HttpStatus.UNAUTHORIZED, "Invalid username or password", req);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorDetails> wrongMethod(HttpRequestMethodNotSupportedException e, WebRequest req) {
		return error(HttpStatus.METHOD_NOT_ALLOWED, "Method " + e.getMethod() + " is not supported here", req);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorDetails> wrongContentType(HttpMediaTypeNotSupportedException e, WebRequest req) {
		return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type not supported; send application/json", req);
	}

	// ---------- 500 ----------

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorDetails> unexpected(Exception e, WebRequest req) {
		log.error("Unhandled error on {}", req.getDescription(false), e);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Please try again.", req);
	}
}
