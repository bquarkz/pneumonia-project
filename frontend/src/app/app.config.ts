import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { includeBearerTokenInterceptor } from 'keycloak-angular';

import { routes } from './app.routes';
import { bearerTokenInterceptorConfigProvider } from './auth/auth.interceptor';
import { provideKeycloakAngular } from './auth/keycloak.config';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideKeycloakAngular(),
    bearerTokenInterceptorConfigProvider,
    provideHttpClient(withInterceptors([includeBearerTokenInterceptor])),
  ],
};
