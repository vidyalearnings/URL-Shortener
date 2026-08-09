# URL Shortener Requirements

The system shall respond to shorten requests within 200ms at p99 for up to 1000 requests/second.
Short codes shall be 7 characters, base62-encoded, and globally unique.
The system shall persist mappings in a relational database with ACID guarantees.
