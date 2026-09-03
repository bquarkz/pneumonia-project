# Attaching Google as a login option (one-time, manual)

This is **not** part of `docker compose up` — Google OAuth requires an app you create by
hand in Google Cloud Console, so this step happens once, before a demo, against an
already-running Keycloak. Until you do this, login still works via the static fallback user
(`demo` / `demo123`, seeded by `keycloak/realm-export.json`) — Google is additive, not a
replacement.

## 1. Create a Google Cloud project

1. Go to [Google Cloud Console](https://console.cloud.google.com/) and create a new project
   (or reuse an existing one) — e.g. "pneumonia-xray-detection".

## 2. Configure the OAuth consent screen

1. In the project, go to **APIs & Services → OAuth consent screen**.
2. User type: **External**.
3. Fill in the required app info (name, support email). Scopes: the default (`email`,
   `profile`, `openid`) is enough — do not add sensitive/restricted scopes.
4. Publishing status: leave it as **Testing**. This avoids Google's app verification review,
   which is unnecessary for a thesis demo, but it means **only pre-approved test-user Google
   accounts can log in** (up to 100).
5. Under **Test users**, add every Google account that will actually log in during your
   evaluation/demo (yours, and anyone on the evaluating committee who wants to try it ahead
   of time). You can add/remove test users at any point later too.

## 3. Create the OAuth 2.0 Client ID

1. Go to **APIs & Services → Credentials → Create Credentials → OAuth client ID**.
2. Application type: **Web application**.
3. Authorized redirect URI — this must match exactly:
   ```
   http://localhost:8080/realms/pneumonia/broker/google/endpoint
   ```
   (Google allows plain `http://localhost` redirect URIs for OAuth clients — no HTTPS/domain
   needed for this local setup.)
4. Save. Copy the generated **Client ID** and **Client secret**.

## 4. Put the credentials into `.env`

Edit the root `.env` file (not `.env.example`):

```
GOOGLE_CLIENT_ID=<paste here>
GOOGLE_CLIENT_SECRET=<paste here>
```

## 5. Add the Identity Provider in Keycloak

The realm import (`keycloak/realm-export.json`) intentionally does **not** bake Google in —
Keycloak's environment-variable substitution for identity-provider secrets is not reliably
documented across versions, so this step is done directly against the running Keycloak Admin
Console instead, which is simple and fully guided by Keycloak's built-in Google template.

With the stack up (`docker compose up`) and Keycloak healthy at `http://localhost:8080`:

1. Log into the Keycloak Admin Console at `http://localhost:8080/admin/master/console/` using
   `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` from your `.env`.
2. Switch to the **pneumonia** realm (top-left realm selector).
3. Go to **Identity providers → Add provider → Google**.
4. Paste the **Client ID** and **Client secret** from Step 3/4 above.
5. Leave the rest of the defaults and **Save**.

That's it — the login page at `http://localhost:4200` (via the `pneumonia-frontend` client)
now shows a "Google" button alongside the username/password form for the static fallback
user.

### Optional: scripted alternative to steps 5

If you'd rather not click through the admin console every time you rebuild the stack from
scratch, the same thing can be done with Keycloak's `kcadm.sh` CLI (available inside the
`keycloak` container) once you're comfortable with it:

```bash
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"

docker compose exec keycloak /opt/keycloak/bin/kcadm.sh create \
  identity-provider/instances -r pneumonia \
  -s alias=google -s providerId=google -s enabled=true \
  -s 'config.clientId="'"$GOOGLE_CLIENT_ID"'"' \
  -s 'config.clientSecret="'"$GOOGLE_CLIENT_SECRET"'"'
```

This is genuinely optional — the admin console steps above are the primary, recommended
path, especially since this is meant as a hands-on learning exercise with Keycloak.

## Known limitation

Because the Google app stays in **Testing** mode, only the test-user accounts you listed in
Step 2 can log in via Google. If someone tries with an account you didn't add, Google will
block them before they even reach Keycloak — that is expected, not a bug. The static fallback
user always works regardless, precisely to cover this case during a live demo.
