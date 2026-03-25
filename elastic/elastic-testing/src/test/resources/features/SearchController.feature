Feature: test SearchController

  Background:
    Given search dataset is prepared once

  Rule: Books

    @Fuzzy
    Scenario: fuzzy search books
      Then fuzzy search for 'embitered' in 'books'
        | name = Frankenstein; or, the modern prometheus | author = Shelley, Mary Wollstonecraft | ranking = 1 | synopsis = "Frankenstein; Or, The Modern Prometheus" by Mary Wollstonecraft Shelley is a Gothic novel published in 1818. It tells the story of Victor Frankenstein, a young scientist who creates a living creature from assembled body parts in an unorthodox experiment. When the creature awakens, Victor flees in horror, abandoning his creation. The conscious being must navigate a world that fears him, learning language and seeking connection, only to face repeated rejection. Embittered and alone, the creature confronts his creator with a desperate request that will set both on a dark path of vengeance and tragedy. (This is an automatically generated summary.) |

    @Interval
    Scenario: interval search books
      Then interval search for 'victor frankenstein' in 'books'
        | name = Frankenstein; or, the modern prometheus | author = Shelley, Mary Wollstonecraft | ranking = 1 | synopsis = "Frankenstein; Or, The Modern Prometheus" by Mary Wollstonecraft Shelley is a Gothic novel published in 1818. It tells the story of Victor Frankenstein, a young scientist who creates a living creature from assembled body parts in an unorthodox experiment. When the creature awakens, Victor flees in horror, abandoning his creation. The conscious being must navigate a world that fears him, learning language and seeking connection, only to face repeated rejection. Embittered and alone, the creature confronts his creator with a desperate request that will set both on a dark path of vengeance and tragedy. (This is an automatically generated summary.) |

    @Span
    Scenario: span search books
      Then span search for 'victor frankenstein' in 'books'
        | name = Frankenstein; or, the modern prometheus | author = Shelley, Mary Wollstonecraft | ranking = 1 | synopsis = "Frankenstein; Or, The Modern Prometheus" by Mary Wollstonecraft Shelley is a Gothic novel published in 1818. It tells the story of Victor Frankenstein, a young scientist who creates a living creature from assembled body parts in an unorthodox experiment. When the creature awakens, Victor flees in horror, abandoning his creation. The conscious being must navigate a world that fears him, learning language and seeking connection, only to face repeated rejection. Embittered and alone, the creature confronts his creator with a desperate request that will set both on a dark path of vengeance and tragedy. (This is an automatically generated summary.) |

    @Wildcard
    Scenario: wildcard search books
      Then wildcard search for 'Pequod' in 'books'
        | name = Moby Dick; Or, The Whale | author = Melville, Herman | ranking = 4 | synopsis = "Moby Dick; Or, The Whale" by Herman Melville is an epic novel published in 1851. Sailor Ishmael narrates the obsessive quest of Captain Ahab, who commands the whaling ship Pequod in pursuit of Moby Dick, a giant white sperm whale that destroyed his leg. Ahab's monomaniacal hunt for vengeance drives the ship and its diverse crew across the world's oceans, blending realistic whaling details with profound explorations of good, evil, fate, and human nature in this cornerstone of American literature. (This is an automatically generated summary.) |

    @All
    Scenario: search books
      Then search for 'Pequod' in 'books'
        | name = Moby Dick; Or, The Whale | author = Melville, Herman | ranking = 4 | synopsis = "Moby Dick; Or, The Whale" by Herman Melville is an epic novel published in 1851. Sailor Ishmael narrates the obsessive quest of Captain Ahab, who commands the whaling ship Pequod in pursuit of Moby Dick, a giant white sperm whale that destroyed his leg. Ahab's monomaniacal hunt for vengeance drives the ship and its diverse crew across the world's oceans, blending realistic whaling details with profound explorations of good, evil, fate, and human nature in this cornerstone of American literature. (This is an automatically generated summary.) |

  Rule: Companies

    @Wildcard
    Scenario: wildcard search companies
      Then wildcard search for 'Cupertino' in 'companies'
        | name = Apple Inc. | ceo = Tim Cook | country = United States | rank = 3 |

    @All
    Scenario: search companies
      Then search for 'Dhahran' in 'companies'
        | name = Saudi Aramco | ceo = Amin H. Al-Nasser | country = Saudi Arabia | rank = 1 |

  Rule: Movies

    @Fuzzy
    Scenario: fuzzy search movies
      Then fuzzy search for 'uxoricyde' in 'movies'
        | name = The Shawshank Redemption | director = Frank Darabont | ranking = 1 | release_year = 1994 | synopsis = A banker convicted of uxoricide forms a friendship over a quarter century with a hardened convict, while maintaining his innocence and trying to remain hopeful through simple compassion. |

    @Interval
    Scenario: interval search movies
      Then interval search for 'hopeful compassion' in 'movies'
        | name = The Shawshank Redemption | director = Frank Darabont | ranking = 1 | release_year = 1994 | synopsis = A banker convicted of uxoricide forms a friendship over a quarter century with a hardened convict, while maintaining his innocence and trying to remain hopeful through simple compassion. |

    @Span
    Scenario: span search movies
      Then span search for 'hopeful compassion' in 'movies'
        | name = The Shawshank Redemption | director = Frank Darabont | ranking = 1 | release_year = 1994 | synopsis = A banker convicted of uxoricide forms a friendship over a quarter century with a hardened convict, while maintaining his innocence and trying to remain hopeful through simple compassion. |

    @Wildcard
    Scenario: wildcard search movies
      Then wildcard search for 'Scottish' in 'movies'
        | name = Braveheart | director = Mel Gibson | ranking = 66 | release_year = 1995 | synopsis = Scottish warrior William Wallace leads his countrymen in a rebellion to free his homeland from the tyranny of King Edward I of England. |

    @All
    Scenario: search movies
      Then search for 'imprisoned' in 'movies'
        | name = Oldboy         | director = Park Chan-wook | ranking = 203 | release_year = 2003 | synopsis = After being kidnapped and imprisoned for fifteen years, Oh Dae-Su is released, only to find that he must track down his captor in five days. |
        | name = Thor: Ragnarok | director = Taika Waititi  | ranking = 122 | release_year = 2017 | synopsis = Imprisoned on the planet Sakaar, Thor must race against time to return to Asgard and stop Ragnarök, the destruction of his world, at the hands of the powerful and ruthless villain Hela. |

  Rule: Music

    @Wildcard
    Scenario: wildcard search music
      Then wildcard search for 'Enola' in 'music'
        | name = Nuclear Attack | band = Sabaton | album = Attero Dominatus | release_year = 2000 | track_number = 2 |

    @All
    Scenario: search music
      Then search for 'Versailles' in 'music'
        | name = Rise of Evil | band = Sabaton | album = Attero Dominatus | release_year = 2000 | track_number = 3 |

  Rule: People

    @Wildcard
    Scenario: wildcard search people
      Then wildcard search for 'Lumbini' in 'people'
        | name = Gautama | surname = Buddha | ranking = 2 | reasons_for_being_famous = Philosopher born in Lumbini, Nepal. |

    @All
    Scenario: search people
      Then search for 'Woolsthorpe' in 'people'
        | name = Isaac | surname = Newton | ranking = 3 | reasons_for_being_famous = Physicist born in Woolsthorpe-by-Colsterworth, United Kingdom. |
