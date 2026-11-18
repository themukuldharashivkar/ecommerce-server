package com.bazaarly.user.domain;

public enum UserRole {

	ROLE_ADMIN,
	/** Read-only admin (public demo): can view the admin panel, cannot change anything. */
	ROLE_ADMIN_VIEWER,
	ROLE_USER
}
