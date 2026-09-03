/**
 * Finite-state-machine status of an X-ray processing request, as persisted by the backend
 * (see DEC-0001, `xray_request.status`).
 */
export type XrayRequestStatus = 'RECEIVED' | 'QUEUED' | 'PROCESSING' | 'RETRYING' | 'DONE' | 'FAILED';

/**
 * Canonical shape shared byte-for-byte with the backend's REST/SSE responses. Field names
 * SHALL NOT deviate from this — see the CONTRACT in PLN-0001 Phase 5.
 */
export interface XrayRequest {
  id: string;
  originalFilename: string;
  status: XrayRequestStatus;
  resultPneumonia: boolean | null;
  resultConfidence: number | null;
  modelVersion: string | null;
  createdAt: string;
}
