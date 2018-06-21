# Documint: An HTTP document processing service

Documint is a web service for processing documents (primarily PDFs) with various
actions: rendering HTML+CSS to PDF; splitting PDFs; generating thumbnails,
etc.

Actions are performed within a session. A session has isolated storage for
documents (both inputs and outputs) that is cleaned up once the session is
terminated. This way a chain of actions may be performed without having to
transfer intermediate results back and forth.

Action results are identified by a URL that may exist before the resource has
any data. Actions will wait for data to exist before continuing, so it is
possible to set up a processing pipeline without having to wait for each
intermediate step to complete first.

See [the docs](https://fusionapp.github.io/clj-documint) for API documentation
and detailed information on the HTTP API.
