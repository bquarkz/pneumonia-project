/**
 * Users module.
 *
 * <p>Links a Keycloak-authenticated subject (the JWT {@code sub} claim - whether the principal
 * arrived via the static fallback login or a federated Google login) to an application-level
 * {@link com.pneumonia.users.models.LocalUser} record, resolving/creating that record on first
 * use.
 *
 * <p>{@code api} is declared a {@code @NamedInterface} so {@code xray} - the only other module
 * that reaches into it, for {@code LocalUserService} - can keep depending on it despite it not
 * being this module's root package.
 */
package com.pneumonia.users;
