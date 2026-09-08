-- Supports the INVALID status: the ml-service's out-of-distribution guardrail rejects an
-- uploaded image (HTTP 422) that does not look like a chest X-ray, and this column persists
-- that rejection's textual reason so it can be shown to the user instead of a bare status.
ALTER TABLE xray_request ADD COLUMN rejection_reason VARCHAR;
