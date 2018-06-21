# Setup

## Requirements

  * Install Java Runtime Environment 8.
  * Install [Boot](http://boot-clj.com/).
  * Install [Leiningen](https://leiningen.org/#install) (for building the uberjar
    and documentation).
  * Checkout clj-documint.

## Development

  * Copy `documint.config.json.example` to `documint.config.json` and adjust the
    configuration to suit your environment. (See below for setting up the keystore
    and truststore if neccessary.)
  * Run `boot dev` to run the hot-reloading development version of the service.
  
    Any dependencies will be downloaded by boot and afterwards the service should
    be running:
  
    ```
    2018-06-20 22:41:41.284:INFO:oejs.ServerConnector:clojure-agent-send-off-pool-0: Started ServerConnector@2034342c{HTTP/1.1}{0.0.0.0:3001}
    2018-06-20 22:41:41.403:INFO:oejs.ServerConnector:clojure-agent-send-off-pool-0: Started ServerConnector@2efbf813{SSL-http/1.1}{0.0.0.0:3002}
    Starting #'documint.systems/dev-system
    nREPL server started on port 60120 on host 127.0.0.1 - nrepl://127.0.0.1:60120
    Elapsed time: 4.072 sec
    ```
    
    At this stage it should be possible to interact with the service:
  
    ```
    $ curl -s -L --data '' http://localhost:3001/sessions/ | python -m json.tool
    {
      "links": {
        "self": "http://localhost:3001/sessions/90a1c5fc-e295-458d-82fa-3e65038fc690",
        "perform": "http://localhost:3001/sessions/90a1c5fc-e295-458d-82fa-3e65038fc690/perform",
        "store-content": "http://localhost:3001/sessions/90a1c5fc-e295-458d-82fa-3e65038fc690/contents/"
      }
    }
    ```

## Production

See `Dockerfile` and `.drone.yml` for production deployment details.

## SSL and client certificate authentication

Documint can do client-certification authentication and SSL itself if necessary:

  * Create a self-signed certificate for SSL:
  
    ```
    $ keytool -genkey \
              -keyalg RSA \
              -alias ssl \
              -keystore documint_keystore_dev.jks \
              -validity 3650 \
              -keysize 2048
    ```
  
  * Obtain a CA certificate. For development purposes the [snake oil CA cert from Fusion](https://raw.githubusercontent.com/fusionapp/fusion/master/fusion/test/services/data/snake-oil-ca.crt.pem) is sufficient.
  
  * Add the CA cert to a truststore, any client requests containing a cert signed
    by this CA will be accepted:
  
    ```sh
    $ keytool -import \
              -file ca.crt.pem \
              -alias some-ca \
              -keystore documint_truststore_uat.jks
    ```
