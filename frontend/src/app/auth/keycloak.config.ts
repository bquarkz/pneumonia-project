import { EnvironmentProviders } from '@angular/core';
import { provideKeycloak } from 'keycloak-angular';

import { environment } from '../../environments/environment';

/**
 * Configures the Keycloak Angular integration against the `pneumonia` realm and the public
 * `pneumonia-frontend` client (public, PKCE S256 — see keycloak/realm-export.json).
 *
 * `onLoad: 'check-sso'` performs a silent (iframe-based) session check on bootstrap instead of
 * an unconditional redirect to the login page: unauthenticated users can still load the shell
 * of the app, and it is `authGuard` (see auth.guard.ts) that decides, per-route, whether to
 * kick off the real Keycloak login redirect.
 */
export function provideKeycloakAngular(): EnvironmentProviders {
  return provideKeycloak({
    config: {
      url: environment.keycloakUrl,
      realm: 'pneumonia',
      clientId: 'pneumonia-frontend',
    },
    initOptions: {
      onLoad: 'check-sso',
      pkceMethod: 'S256',
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    },
  });
}
