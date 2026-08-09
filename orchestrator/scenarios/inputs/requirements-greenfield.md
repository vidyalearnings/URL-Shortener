# Requirement: URL Shortener Service

Build a URL shortener with the following, precisely specified, behavior:

- `POST /api/urls` creates a shortened URL. It accepts an optional caller-supplied
  custom alias (3-20 characters, `[a-zA-Z0-9_-]`) and an optional ISO-8601 expiry
  timestamp. Requests may include an `Idempotency-Key` header; replaying the same
  key with an identical body must return the original result instead of creating a
  duplicate record.
- `GET /{code}` returns an HTTP 302 redirect to the original URL. It returns 404 if
  the code does not exist or has been deleted, and 410 if it has expired. Click
  metadata (referrer, user agent, a hashed IP address) is recorded for every
  successful redirect.
- `GET /api/urls/{code}/analytics` returns total click count, a per-day click count
  breakdown, and the top referrers and user agents by click count.
- `PUT /api/urls/{code}` updates the destination URL and/or expiry.
- `DELETE /api/urls/{code}` soft-deletes a URL (status transitions to DELETED; it no
  longer resolves).
- Only `http` and `https` schemes are accepted for the destination URL; all other
  schemes are rejected with HTTP 400.
- Concurrent creation requests that generate colliding short codes must not corrupt
  data: the database's unique constraint on `short_code` is the source of truth, and
  a colliding request is retried with a newly generated code up to 5 attempts before
  returning HTTP 503.
- Requests are rate-limited per client IP: 20 requests per window, refilling at 5
  requests per second.
