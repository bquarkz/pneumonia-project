import { Component, ElementRef, inject, output, signal, viewChild } from '@angular/core';

import { XrayApiService } from '../xray-api.service';
import { XrayRequest } from '../xray-request.model';

const ACCEPTED_MIME_TYPE = 'image/jpeg';
const ACCEPTED_EXTENSIONS = ['.jpg', '.jpeg'];
const MAX_BATCH_SIZE = 10;

function isJpeg(file: File): boolean {
  if (file.type === ACCEPTED_MIME_TYPE) {
    return true;
  }
  const lowerName = file.name.toLowerCase();
  return ACCEPTED_EXTENSIONS.some((extension) => lowerName.endsWith(extension));
}

/**
 * Lets the user pick multiple JPEG files (up to {@link MAX_BATCH_SIZE}) and submit them as a
 * batch (Step 5.3). Successive picks accumulate into the staged batch rather than replacing it
 * - a native `<input type="file">`'s `FileList` only ever holds the most recent pick, so each
 * new selection is merged into {@link selectedFiles} instead of overwriting it. Client-side
 * filtering is purely a UX convenience — the backend remains the authority on both file-type
 * and batch-size validation, rejecting non-JPEG parts or batches over the limit with 400.
 */
@Component({
  selector: 'app-xray-upload',
  imports: [],
  templateUrl: './xray-upload.component.html',
  styleUrl: './xray-upload.component.scss',
})
export class XrayUploadComponent {
  private readonly xrayApi = inject(XrayApiService);
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');

  /** Emits the created XrayRequest objects (status RECEIVED/QUEUED) once the batch is accepted. */
  readonly uploaded = output<XrayRequest[]>();

  protected readonly selectedFiles = signal<File[]>([]);
  protected readonly rejectedFileNames = signal<string[]>([]);
  protected readonly batchLimitMessage = signal<string | null>(null);
  protected readonly isSubmitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected onFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const newFiles = Array.from(input.files ?? []);
    const newJpegFiles = newFiles.filter(isJpeg);

    const alreadySelectedNames = new Set(this.selectedFiles().map((file) => file.name));
    const merged = [
      ...this.selectedFiles(),
      ...newJpegFiles.filter((file) => !alreadySelectedNames.has(file.name)),
    ];

    this.selectedFiles.set(merged.slice(0, MAX_BATCH_SIZE));
    this.rejectedFileNames.set(newFiles.filter((file) => !isJpeg(file)).map((file) => file.name));
    this.batchLimitMessage.set(
      merged.length > MAX_BATCH_SIZE
        ? `Only the first ${MAX_BATCH_SIZE} files were kept — a batch accepts at most ${MAX_BATCH_SIZE}.`
        : null,
    );
    this.errorMessage.set(null);

    // Reset so picking the exact same file(s) again still fires `change` - otherwise some
    // browsers suppress the event when the native input's value would be unchanged.
    input.value = '';
  }

  protected removeFile(name: string): void {
    this.selectedFiles.update((files) => files.filter((file) => file.name !== name));
    this.batchLimitMessage.set(null);
  }

  protected submit(): void {
    const files = this.selectedFiles();
    if (files.length === 0 || this.isSubmitting()) {
      return;
    }

    this.isSubmitting.set(true);
    this.errorMessage.set(null);

    this.xrayApi.uploadBatch(files).subscribe({
      next: (created) => {
        this.isSubmitting.set(false);
        this.selectedFiles.set([]);
        this.rejectedFileNames.set([]);
        this.batchLimitMessage.set(null);
        const input = this.fileInput()?.nativeElement;
        if (input) {
          input.value = '';
        }
        this.uploaded.emit(created);
      },
      error: (error: unknown) => {
        this.isSubmitting.set(false);
        this.errorMessage.set('Upload failed. Please try again.');
        console.error('Batch upload failed', error);
      },
    });
  }
}
