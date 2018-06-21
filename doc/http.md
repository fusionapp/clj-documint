# HTTP API

The API is intended to be self-describing, each response contains information
about relevant related resources. Interaction with the service usually begins by
creating a session.

JSON results will have a `links` key that is a mapping of relationships to URLs.
For example `self` will be a URL to the current (or newly redirected to)
resource, while `perform` will be a URL to which an action can be `POST`-ed.

## Create a session

`POST /sessions` (`application/json`)

Create a new session. Takes no parameters. Will redirect to the newly created
session.

### Links

  * `self`: URL for the newly created session.
  * [`perform`](#Perform-an-action): `POST` to this resource to perform an action.
  * [`store-content`](#Store-content): `POST` to this resource to store new
    content, such as uploading a document to process.
  
  
## Perform an action

`POST /<session_resource>/perform` (`application/json`)

Perform an action within a session. Action inputs and outputs are specific to
each action being performed. See [Actions](actions.html).

All input and output documents are specified as URIs.

### Parameters

```json
{
  "action": "action-name",
  "parameters": {
    "specific": "parameters"
  }
}
```

### Links

The links for action results vary from action to action.


## Store content

`POST /<session_resource>/contents` (Specify `Content-Type`)

Store new session content. Takes no parameters. Will redirect to the newly
created content.

### Links

  * `self`: URL for the newly created content.


## Fetching content

`GET /<session_resource>/contents/<content_id>`

Fetch the content of an output. Takes no parameters.


## Deleting a session.

`DELETE /<session_resource>`

Delete a session and clean up all associated storage. Takes no parameters.


## Prometheus metrics.

`GET /metrics`

Endpoint for Prometheus to poll for metrics.
