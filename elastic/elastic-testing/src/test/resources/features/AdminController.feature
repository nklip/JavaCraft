Feature: test AdminController

  Scenario: ingest books
    Given index 'books' is recreated
    Then ingest 'data/json/books.json' json file in 'books' index

  Scenario: ingest companies
    Given index 'companies' is recreated
    Then ingest 'data/json/companies.json' json file in 'companies' index

  Scenario: ingest movies
    Given index 'movies' is recreated
    Then ingest 'data/json/movies.json' json file in 'movies' index

  Scenario: ingest music
    Given index 'music' is recreated
    Then ingest 'data/json/music.json' json file in 'music' index

  Scenario: ingest people
    Given index 'people' is recreated
    Then ingest 'data/json/people.json' json file in 'people' index