import { ActivatedRouteSnapshot, CanActivateFn, RouterStateSnapshot } from '@angular/router';
import { AuthGuardData, createAuthGuard } from 'keycloak-angular';

/**
 * Route guard for the app's authenticated shell. If the user already has a session
 * (checked silently at bootstrap via `check-sso`, see keycloak.config.ts), access is
 * granted. Otherwise it triggers the real Keycloak login redirect — the realm's login page,
 * which shows the static `demo`/`demo123` fallback user form and (once the Google IdP is
 * configured per scripts/configure-google-idp.md) a "Google" button.
 */
const isAccessAllowed = async (
  _route: ActivatedRouteSnapshot,
  _state: RouterStateSnapshot,
  authData: AuthGuardData,
): Promise<boolean> => {
  const { authenticated, keycloak } = authData;

  if (authenticated) {
    return true;
  }

  await keycloak.login({
    redirectUri: window.location.href,
  });

  return false;
};

export const authGuard: CanActivateFn = createAuthGuard(isAccessAllowed);
