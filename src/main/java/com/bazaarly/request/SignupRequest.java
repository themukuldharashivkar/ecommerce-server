package com.bazaarly.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Signup body. Replaces binding the User entity directly, so clients can only set these fields
 * (never role, id or addresses). Password rules: 8-72 characters (72 is BCrypt's limit),
 * at least one letter and one digit.
 */
public record SignupRequest(
		@NotBlank(message = "First name is required") @Size(max = 100) String firstName,
		@NotBlank(message = "Last name is required") @Size(max = 100) String lastName,
		@NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") @Size(max = 255) String email,
		@NotBlank(message = "Password is required")
		@Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
		@Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "Password must contain at least one letter and one number")
		String password) {
}
