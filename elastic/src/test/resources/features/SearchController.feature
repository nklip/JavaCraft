Feature: test SearchController

  Background:
    Given index 'movies' exists
    Then ingest 'data/json/movies.json' json file with 250 entities in 'movies' index

  Scenario: wildcard search
    Then wildcard search for 'Scottish' in 'movies'
      | name = Braveheart | director = Mel Gibson | ranking = 91 | release_year = 1995 | synopsis = Scottish warrior William Wallace leads his countrymen in a rebellion to free his homeland from the tyranny of King Edward I of England. |

  Scenario: fuzzy search
    Then fuzzy search for 'Skywadker' in 'movies'
      | name = Star Wars: Episode V - The Empire Strikes Back | director = Irvin Kershner | ranking = 15 | release_year = 1980 | synopsis = After the Rebels are overpowered by the Empire, Luke Skywalker begins his Jedi training with Yoda, while his friends are pursued across the galaxy by Darth Vader and bounty hunter Boba Fett. |
      | name = Star Wars                                      | director = George Lucas   | ranking = 16 | release_year = 1977 | synopsis = Luke Skywalker joins forces with a Jedi Knight, a cocky pilot, a Wookiee and two droids to save the galaxy from the Empire's world-destroying battle station, while also attempting to rescue Princess Leia from the mysterious Darth ... |

  Scenario: span search
    Then span search for 'redemption compassion' in 'movies'
      | name = The Shawshank Redemption | director = Frank Darabont | ranking = 1 | release_year = 1994 | synopsis = Over the course of several years, two convicts form a friendship, seeking consolation and, eventually, redemption through basic compassion. |

  Scenario: search
    Then search for 'imprisoned' in 'movies'
      | name = A Clockwork Orange | director = Stanley Kubrick | ranking = 115 | release_year = 1971 | synopsis = In the future, a sadistic gang leader is imprisoned and volunteers for a conduct-aversion experiment, but it doesn't go as planned.   |
      | name = Oldeuboi           | director = Park Chan-wook  | ranking = 92  | release_year = 2003 | synopsis = After being kidnapped and imprisoned for fifteen years, Oh Dae-Su is released, only to find that he must find his captor in five days.|
