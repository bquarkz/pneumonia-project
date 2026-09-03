import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import Keycloak from 'keycloak-js';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { XrayRequest } from './xray-request.model';

const STATUS_UPDATE_EVENT = 'status-update';

/**
 * Typed wrapper around the four backend endpoints from the CONTRACT (PLN-0001 Phase 5 / Step
 * 5.2). All requests go to `environment.backendUrl`, which the bearer-token interceptor
 * (see auth/auth.interceptor.ts) recognizes and attaches the Keycloak access token to.
 */
@Injectable({ providedIn: 'root' })
export class XrayApiService {
  private readonly http = inject(HttpClient);
  private readonly keycloak = inject(Keycloak);
  private readonly baseUrl = `${environment.backendUrl}/api/xray-requests`;

  /** POST /api/xray-requests/batch — multipart, repeated part name "files". */
  uploadBatch(files: File[]): Observable<XrayRequest[]> {
    const formData = new FormData();
    for (const file of files) {
      formData.append('files', file, file.name);
    }
    return this.http.post<XrayRequest[]>(`${this.baseUrl}/batch`, formData);
  }

  /** GET /api/xray-requests — all requests for the authenticated user, createdAt desc. */
  list(): Observable<XrayRequest[]> {
    return this.http.get<XrayRequest[]>(this.baseUrl);
  }

  /** POST /api/xray-requests/{id}/retry — only valid while status is FAILED. */
  retry(id: string): Observable<XrayRequest> {
    return this.http.post<XrayRequest>(`${this.baseUrl}/${id}/retry`, null);
  }

  /**
   * GET /api/xray-requests/stream — Server-Sent Events. The native `EventSource` API cannot
   * set an `Authorization` header, so the bearer token is passed as the `access_token` query
   * parameter instead (the one route the backend's custom `BearerTokenResolver` accepts this
   * on). Emits every "status-update" event, JSON-parsed into an `XrayRequest`. The
   * `EventSource` is closed when the returned `Observable` is unsubscribed.
   */
  streamStatus(): Observable<XrayRequest> {
    return new Observable<XrayRequest>((subscriber) => {
      const token = encodeURIComponent(this.keycloak.token ?? '');
      const eventSource = new EventSource(`${this.baseUrl}/stream?access_token=${token}`);

      const onStatusUpdate = (event: Event): void => {
        try {
          const data = (event as MessageEvent<string>).data;
          subscriber.next(JSON.parse(data) as XrayRequest);
        } catch (error) {
          console.error('Failed to parse status-update SSE payload', error);
        }
      };

      const onError = (event: Event): void => {
        // The browser's EventSource auto-reconnects on transient network errors, so this is
        // logged (not surfaced as an Observable error, which would permanently end the stream).
        console.error('X-ray status stream connection error', event);
      };

      eventSource.addEventListener(STATUS_UPDATE_EVENT, onStatusUpdate);
      eventSource.addEventListener('error', onError);

      return () => {
        eventSource.removeEventListener(STATUS_UPDATE_EVENT, onStatusUpdate);
        eventSource.removeEventListener('error', onError);
        eventSource.close();
      };
    });
  }
}
