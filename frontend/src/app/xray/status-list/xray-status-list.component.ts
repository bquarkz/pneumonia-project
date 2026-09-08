import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Component, inject, signal } from '@angular/core';

import { XrayApiService } from '../xray-api.service';
import { XrayRequest } from '../xray-request.model';

/**
 * Shows every request's live status (Step 5.4). Seeds itself from `list()` on construction,
 * then keeps itself current by upserting-by-id both the batch-upload response (via
 * `upsertMany`, called by the dashboard shell) and every `status-update` SSE event — so the
 * table is correct on a fresh page load and stays correct live, without ever needing a
 * duplicate row for the same request id.
 */
@Component({
  selector: 'app-xray-status-list',
  imports: [],
  templateUrl: './xray-status-list.component.html',
  styleUrl: './xray-status-list.component.scss',
})
export class XrayStatusListComponent {
  private readonly xrayApi = inject(XrayApiService);

  protected readonly requests = signal<XrayRequest[]>([]);
  protected readonly retryingIds = signal<ReadonlySet<string>>(new Set<string>());
  protected readonly loadError = signal<string | null>(null);
  protected readonly viewingImage = signal<{ url: string; filename: string } | null>(null);
  protected readonly imageLoadError = signal<string | null>(null);

  constructor() {
    this.xrayApi.list().subscribe({
      next: (data) => this.requests.set(data),
      error: (error: unknown) => {
        this.loadError.set('Could not load existing X-ray requests.');
        console.error('Failed to load X-ray requests', error);
      },
    });

    this.xrayApi
      .streamStatus()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (update) => this.upsertOne(update),
        error: (error: unknown) => console.error('X-ray status stream failed', error),
      });
  }

  /** Called by the dashboard shell right after a batch upload succeeds. */
  upsertMany(updates: readonly XrayRequest[]): void {
    for (const update of updates) {
      this.upsertOne(update);
    }
  }

  protected retry(id: string): void {
    if (this.retryingIds().has(id)) {
      return;
    }
    this.setRetrying(id, true);
    this.xrayApi.retry(id).subscribe({
      next: (updated) => {
        this.upsertOne(updated);
        this.setRetrying(id, false);
      },
      error: (error: unknown) => {
        console.error(`Retry failed for request ${id}`, error);
        this.setRetrying(id, false);
      },
    });
  }

  protected formatConfidence(value: number | null): string {
    return value === null ? '—' : `${(value * 100).toFixed(1)}%`;
  }

  protected viewImage(request: XrayRequest): void {
    this.imageLoadError.set(null);
    this.xrayApi.getImage(request.id).subscribe({
      next: (blob) => {
        // Revokes whatever URL is currently held (e.g. a second click fired while the first
        // fetch of a multi-MB JPEG was still in flight, before the modal covers the buttons)
        // rather than leaking it by overwriting the signal outright.
        this.revokeCurrentImage();
        this.viewingImage.set({ url: URL.createObjectURL(blob), filename: request.originalFilename });
      },
      error: (error: unknown) => {
        this.imageLoadError.set(`Could not load image for ${request.originalFilename}.`);
        console.error(`Failed to load image for request ${request.id}`, error);
      },
    });
  }

  protected closeImage(): void {
    this.revokeCurrentImage();
    this.viewingImage.set(null);
  }

  private revokeCurrentImage(): void {
    const current = this.viewingImage();
    if (current) {
      URL.revokeObjectURL(current.url);
    }
  }

  private setRetrying(id: string, retrying: boolean): void {
    this.retryingIds.update((current) => {
      const next = new Set(current);
      if (retrying) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  private upsertOne(update: XrayRequest): void {
    this.requests.update((current) => {
      const index = current.findIndex((request) => request.id === update.id);
      if (index === -1) {
        return [update, ...current];
      }
      const next = current.slice();
      next[index] = update;
      return next;
    });
  }
}
