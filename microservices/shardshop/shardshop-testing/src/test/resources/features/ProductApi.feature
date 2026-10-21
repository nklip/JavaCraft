@product-api
Feature: Product seller and product API on PostgreSQL shards
  Product runs from its packaged quarkus-app folder against four shard databases that Flyway migrated. Two shards
  have the region US, thus a region can have more than one shard. Product connects with verify-full TLS, uses at most
  two connections for each shard endpoint, and gets a new generator ID at each start. The expected bodies come from
  the product OpenAPI document: an error body has only a code and a nonempty message.
  After each scenario, a hook stops product and checks that no product JVM logged a warning or an error.

  Background:
    Given the catalog shards "shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US" that Flyway migrated
    And product runs against the shard primaries

  Scenario: One seller key makes a separate seller in each region, on the shard that the key selects
    When a client creates these sellers with the key "shared-region-key":
      | seller | companyName            | region |
      | us     | Synthetic US company   | US     |
      | eu     | Synthetic EU company   | EU     |
      | asia   | Synthetic ASIA company | ASIA   |
    Then each creation returns 201 with the requested seller, a new seller ID and policy-appropriate zero profits
    And the sellers "us, eu, asia" have different IDs
    And each seller is stored only on the shard that its key selects, and its ID routes to that shard
    And an identical retry of each creation returns 200 with the same seller
    And a GET by ID and a GET by creation key return each seller

  Scenario: Sellers of one region go to all shards of that region
    When a client creates the US sellers "first" and "second" with keys that select different US shards
    Then each seller is stored only on the shard that its key selects, and its ID routes to that shard

  Scenario: A product is created on the shard of its seller, and its key is scoped to the seller
    Given the seller "owner" "Owner company" in "US" with the key "owner"
    And the seller "other" "Other company" in "EU" with the key "other"
    When a client creates the product "item" of the seller "owner" with the key "shared-item-key"
    Then the creation returns 201 with the requested product, a new product ID and the stock 2
    And the product "item" is stored only on the shard of the seller "owner"
    And a GET by ID and a GET by creation key return the product "item"
    When a client creates the product "other-item" of the seller "other" with the key "shared-item-key"
    Then the creation returns 201 with the requested product, a new product ID and the stock 2
    And the products "item" and "other-item" have different IDs

  Scenario: A used key with a changed payload is a conflict, and a missing entity is reported
    Given the seller "owner" "Owner company" in "US" with the key "owner"
    And the seller "other" "Other company" in "EU" with the key "other"
    And the product "item" of the seller "owner" with the key "item"
    Then these requests return their error:
      | method | path                                                 | key            | body                         | status | code                         |
      | POST   | /api/v1/sellers                                      | owner          | {"companyName":"Changed","region":"US","countryCode":"US"} | 409 | SELLER_IDEMPOTENCY_CONFLICT |
      | POST   | /api/v1/sellers/{seller:owner}/products              | item           | item+{"name":"Changed"}      | 409    | PRODUCT_IDEMPOTENCY_CONFLICT |
      | POST   | /api/v1/sellers/{seller:owner}/products              | item           | item+{"description":"Changed"} | 409  | PRODUCT_IDEMPOTENCY_CONFLICT |
      | POST   | /api/v1/sellers/{seller:owner}/products              | item           | item+{"price":"20.00"}       | 409    | PRODUCT_IDEMPOTENCY_CONFLICT |
      | POST   | /api/v1/sellers/{seller:owner}/products              | item           | item+{"unitCost":"13.00"}    | 409    | PRODUCT_IDEMPOTENCY_CONFLICT |
      | POST   | /api/v1/sellers/{seller:owner}/products              | item           | item+{"currency":"EUR"}      | 409    | PRODUCT_IDEMPOTENCY_CONFLICT |
      | POST   | /api/v1/sellers/{seller:owner}/products              | item           | item+{"initialStock":3}      | 409    | PRODUCT_IDEMPOTENCY_CONFLICT |
      | GET    | /api/v1/sellers/by-key/missing?region=US             |                |                              | 404    | SELLER_NOT_FOUND             |
      | GET    | /api/v1/sellers/{seller:owner}/products/by-key/missing |              |                              | 404    | PRODUCT_NOT_FOUND            |
      | GET    | /api/v1/sellers/{product:item}                       |                |                              | 404    | SELLER_NOT_FOUND             |
      | POST   | /api/v1/sellers/{product:item}/products              | missing-parent | item+{}                      | 422    | SELLER_NOT_FOUND             |
      | GET    | /api/v1/sellers/{seller:owner}/products/{seller:owner} |              |                              | 404    | PRODUCT_NOT_FOUND            |
      | GET    | /api/v1/sellers/{seller:other}/products/{product:item} |              |                              | 404    | PRODUCT_NOT_FOUND            |
    And a GET by ID returns the seller "owner" and the product "item" without changes

  Scenario: A retry returns the current stock and profits, and another JSON form of the same integer is the same payload
    Given the seller "owner" "Owner company" in "US" with the key "owner"
    And the product "item" of the seller "owner" with the key "item"
    And the database sets the stock of the product "item" to 1 and the USD profit of the seller "owner" to "-7.45"
    When a client retries the creation of the seller "owner"
    Then the creation returns 200 with the same seller and the profits USD "-7.45" and EUR "0.00"
    When a client retries the creation of the product "item" with the initial stock written as "2.0"
    Then the creation returns 200 with the same product and the stock 1
    When a client retries the creation of the product "item" with the initial stock written as "2e0"
    Then the creation returns 200 with the same product and the stock 1

  Scenario: Concurrent identical creations make one entity
    When 8 clients create the seller "concurrent" "Concurrent company" in "EU" with the key "concurrent-seller" at the same time
    Then exactly one creation returns 201, and all creations return the same seller
    When 8 clients create the product "concurrent-item" of the seller "concurrent" with the key "concurrent-product" at the same time
    Then exactly one creation returns 201, and all creations return the same product

  Scenario: A client that loses a creation response recovers the same seller with the same key
    When a client sends the creation of the seller "lost" "Lost response company" in "ASIA" with the key "dropped-response" and closes the connection without the response
    And a client retries the creation of the seller "lost"
    Then a GET by creation key returns the seller "lost"
    And the seller "lost" is stored only on the shard that its key selects, and its ID routes to that shard
    When a client sends the creation of the seller "unread" "Lost response company" in "ASIA" with the key "committed-unread" and does not read the response
    Then a GET by creation key finds the seller "unread" within 5 seconds
    And a retry of the creation of the seller "unread" returns 200 with the same seller

  Scenario: Product rejects invalid input with 400 INVALID_REQUEST and stores nothing
    Given the seller "owner" "Owner company" in "US" with the key "owner"
    Then these seller creation bodies return 400 INVALID_REQUEST:
      | body                                                                  |
      | {"companyName":"","region":"US"}                                      |
      | {"companyName":"\u00a0","region":"US"}                                |
      | {"companyName":"\u0085","region":"US"}                                |
      | {"companyName":"\u0000","region":"US"}                                |
      | {"companyName":"\ud800","region":"US"}                                |
      | {"companyName":"{repeat:x:201}","region":"US"}                        |
      | {"companyName":123,"region":"US"}                                     |
      | {"companyName":null,"region":"US"}                                    |
      | {"companyName":"Valid company","region":"AFRICA"}                     |
      | {"companyName":"Valid company","region":"US","sellerId":"{seller:owner}"} |
      | {"companyName":"Valid company","region":"US","profitsEarned":{"USD":"0.00"}} |
      | []                                                                    |
      | null                                                                  |
      | {                                                                     |
      | {"companyName":"A","companyName":"B","region":"US"}                   |
      | {"companyName":"\xff","region":"US"}                                  |
      | {"companyName":"A","region":"US"}{}                                   |
    And these product creation bodies for the seller "owner" return 400 INVALID_REQUEST:
      | body                                          |
      | item+{"name":"\u00a0"}                        |
      | item+{"description":""}                       |
      | item+{"description":"{repeat:x:2001}"}        |
      | item+{"price":"01.00"}                        |
      | item+{"price":1}                              |
      | item+{"unitCost":"-0.01"}                     |
      | item+{"currency":"usd"}                       |
      | item+{"initialStock":2.5}                     |
      | item+{"initialStock":true}                    |
      | item+{"initialStock":"2"}                     |
      | item+{"initialStock":2147483648}              |
      | item+{"initialStock":2.000000000000000000001} |
      | item+{"stock":2}                              |
      | item+{"productId":"{seller:owner}"}           |
    And these Idempotency-Key headers of a valid seller creation return 400 INVALID_REQUEST:
      | key            |
      | <none>         |
      | <empty>        |
      | bad key        |
      | -bad           |
      | {repeat:x:129} |
      | <twice>        |
    And these Content-Type headers of a valid seller creation return 400 INVALID_REQUEST:
      | contentType                           |
      | text/plain                            |
      | not-a-media-type                      |
      | application/json; charset             |
      | <none>                                |
      | multipart/form-data                   |
      | multipart/form-data; boundary         |
      | multipart/form-data; boundary=""      |
      | multipart/form-data; boundary=example |
    And these requests return 400 INVALID_REQUEST:
      | method | path                                               | body                       |
      | POST   | /api/v1/sellers?unknown=1                          | seller                     |
      | GET    | /api/v1/sellers/{seller:owner}?region=US           |                            |
      | GET    | /api/v1/sellers/by-key/missing                     |                            |
      | GET    | /api/v1/sellers/by-key/missing?region=US&region=US |                            |
      | GET    | /api/v1/sellers/by-key/missing?region=US&unknown=1 |                            |
      | GET    | /api/v1/sellers/by-key/missing?region=AFRICA       |                            |
      | POST   | /api/v1/sellers                                    | <none>                     |
      | POST   | /api/v1/sellers                                    | <empty>                    |
      | POST   | /api/v1/sellers                                    | <empty chunked>            |
      | POST   | /api/v1/sellers/{seller:owner}/products            | <none>                     |
      | POST   | /api/v1/sellers/{seller:owner}/products            | <empty>                    |
      | POST   | /api/v1/sellers/{seller:owner}/products            | <empty chunked>            |
    And these seller and product IDs in a path return 400 INVALID_REQUEST:
      | id                  |
      | 0                   |
      | 01                  |
      | -1                  |
      | %2B1                |
      | 1.0                 |
      | 1e0                 |
      | 9223372036854775808 |
      | %201                |
      | 1%0A                |
    And the shards store only the seller "owner"

  Scenario: Product keeps the largest valid values and supplementary Unicode characters
    Given the seller "owner" "Owner company" in "US" with the key "owner"
    When a client creates the product "boundary" of the seller "owner" with the key "valid-boundary" and these fields:
      | name         | {repeat:🛍:200}       |
      | description  | {repeat:界:2000}      |
      | price        | 99999999999999999.99 |
      | unitCost     | 0.00                 |
      | initialStock | 2147483647           |
    Then the creation returns 201 with the requested product, a new product ID and the stock 2147483647
    When a client creates the seller "unicode" "{repeat:🛍:200}" in "US" with the key "unicode-scalars"
    Then the creation returns 201 with the requested seller, a new seller ID and policy-appropriate zero profits

  Scenario: Product accepts a request body of 32 KiB and rejects a larger body
    When a client creates the seller "boundary" "Body boundary company" in "US" with the key "body-boundary" in a body of exactly 32768 bytes
    Then the creation returns 201 with the requested seller, a new seller ID and policy-appropriate zero profits
    And these seller creations with a body of more than 32768 bytes return 400 INVALID_REQUEST and store nothing:
      | key                | framing                       |
      | oversized-fixed    | Content-Length                |
      | oversized-chunked  | chunked                       |
      | oversized-declared | Content-Length header only    |

  Scenario: A request body that stops arriving ends with 503 within the server deadline and creates nothing
    When a client sends the first byte of the creation body of a US seller with the key "slow-body" and then waits
    Then product returns 503 CATALOG_UNAVAILABLE in less than 4.5 seconds
    When the client sends the rest of the body on the same connection
    Then a GET by the creation key "slow-body" in "US" returns 404 SELLER_NOT_FOUND

  Scenario: A held creation lock and a full connection pool give 503 within the deadline, and product recovers
    Given the seller "owner" "Owner company" in "US" with the key "owner"
    And a database session holds the creation lock of a US seller key on the shard of the seller "owner"
    When 2 clients create a US seller with that key at the same time
    Then 2 sessions of product wait for that lock
    And a GET of the seller "owner" returns 503 CATALOG_UNAVAILABLE in less than 4 seconds
    And product has at most 2 sessions on that shard
    And both creations return 503 CATALOG_UNAVAILABLE in less than 4 seconds
    When the database session releases the lock
    Then a GET by that creation key returns 404 SELLER_NOT_FOUND within 5 seconds
    And a retried creation with that key returns 201, and the next retry returns 200 with the same seller

  Scenario: A restart of product keeps the stored entities
    Given the seller "owner" "Owner company" in "US" with the key "owner"
    And the product "item" of the seller "owner" with the key "item"
    When product restarts with a new generator ID
    Then a retry of the creation of the seller "owner" returns 200 with the same seller
    And a retry of the creation of the product "item" returns 200 with the same product

  Scenario: Without reachable primaries, product returns 503 within the deadline and still rejects invalid input first
    Given the seller "owner" "Owner company" in "US" with the key "owner"
    And the product "item" of the seller "owner" with the key "item"
    When product restarts with primary endpoints that refuse connections
    Then the reads of the seller "owner" and the product "item" return 503 CATALOG_UNAVAILABLE in less than 4 seconds
    And these seller creation bodies return 400 INVALID_REQUEST:
      | body                                   |
      | {"companyName":"\u00a0","region":"US"} |

  Scenario: The replica read profile never reads a primary, and a creation still writes to a primary
    Given the seller "owner" "Owner company" in "US" with the key "owner"
    And the product "item" of the seller "owner" with the key "item"
    When product restarts with the replica read profile and replica endpoints that refuse connections
    Then the reads of the seller "owner" and the product "item" return 503 READ_REPLICA_UNAVAILABLE in less than 4 seconds
    When a client creates the seller "written" "Replica profile write" in "EU" with the key "replica-profile-write"
    Then the creation returns 201 with the requested seller, a new seller ID and policy-appropriate zero profits
    When product restarts against the shard primaries
    Then a GET by ID and a GET by creation key return the seller "written"
