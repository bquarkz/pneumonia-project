/**
 * Runtime configuration for the Angular app.
 *
 * These two values default to local `ng serve` development targets. For the production
 * Docker image, `frontend/Dockerfile` overwrites this exact file (before `ng build` runs)
 * with values derived from the `BACKEND_PUBLIC_URL` / `KEYCLOAK_PUBLIC_URL` build ARGs that
 * `docker-compose.yml` passes in, so no `localhost` literal ever ships in the production
 * bundle. Do not import `environment` anywhere except through this file's exported object —
 * that keeps the Dockerfile's generated replacement a drop-in.
 */
export const environment = {
  keycloakUrl: 'http://localhost:8080',
  backendUrl: 'http://localhost:8081',
};
