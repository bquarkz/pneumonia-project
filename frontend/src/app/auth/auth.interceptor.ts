import { Provider } from '@angular/core';
import {
  createInterceptorCondition,
  IncludeBearerTokenCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
} from 'keycloak-angular';

import { environment } from '../../environments/environment';

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Tells keycloak-angular's `includeBearerTokenInterceptor` (registered in app.config.ts) to
 * attach the current Keycloak bearer token to every HTTP request whose URL starts with the
 * backend's base URL — and nowhere else (e.g. not to Keycloak's own endpoints).
 */
const backendUrlCondition = createInterceptorCondition<IncludeBearerTokenCondition>({
  urlPattern: new RegExp(`^${escapeRegExp(environment.backendUrl)}(/.*)?$`, 'i'),
  bearerPrefix: 'Bearer',
});

export const bearerTokenInterceptorConfigProvider: Provider = {
  provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  useValue: [backendUrlCondition],
};
