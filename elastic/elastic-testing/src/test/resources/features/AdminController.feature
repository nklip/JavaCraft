Feature: test AdminController

  Scenario: ingest movies
    Given index 'movies' exists
    Then ingest 'data/json/movies.json' json file in 'movies' index

  Scenario: ingest books
    Given index 'books' exists
    Then ingest 'data/json/books.json' json file in 'books' index

  Scenario: ingest music
    Given index 'music' exists
    Then ingest 'data/json/music.json' json file in 'music' index