package com.bazaarly.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * List endpoints keep returning a plain JSON array (so existing clients keep working) and report the
 * total number of items in the X-Total-Count header. Page and size are validated on each endpoint.
 */
final class Paged {

	static final String TOTAL_COUNT = "X-Total-Count";

	private Paged() {
	}

	static <T> ResponseEntity<List<T>> list(Page<T> page, HttpStatus status) {
		return ResponseEntity.status(status)
				.header(TOTAL_COUNT, String.valueOf(page.getTotalElements()))
				.body(page.getContent());
	}
}
