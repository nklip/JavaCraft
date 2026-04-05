Feature: OpenFlights full ingestion

  Scenario: Ingesting all producer datasets stores the expected dataset in PostgreSQL
    When all OpenFlights datasets are ingested through the producer endpoints in the documented order
    Then PostgreSQL eventually contains the expected OpenFlights dataset
      | countries | airlines | airports | planes | routes | routeEquipmentCodes |
      | 260       | 6162     | 7810     | 246    | 67663  | 93231               |
    And PostgreSQL estimates a non-negative full-ingestion duration
