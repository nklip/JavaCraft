Feature: test SearchController

  Background:
    Given search dataset is prepared once

  Rule: Books

    @Fuzzy
    Scenario: fuzzy search books
      Then fuzzy search for 'embitered' in 'books'
        | name = Frankenstein; or, the modern prometheus | author = Shelley, Mary Wollstonecraft | ranking = 1 | synopsis = "Frankenstein; Or, The Modern Prometheus" by Mary Wollstonecraft Shelley is a Gothic novel published in 1818. It tells the story of Victor Frankenstein, a young scientist who creates a living creature from assembled body parts in an unorthodox experiment. When the creature awakens, Victor flees in horror, abandoning his creation. The conscious being must navigate a world that fears him, learning language and seeking connection, only to face repeated rejection. Embittered and alone, the creature confronts his creator with a desperate request that will set both on a dark path of vengeance and tragedy. (This is an automatically generated summary.) |
      Then fuzzy search for 'Herman Meeville, Mariner' in 'books'
        | name = Herman Melville, Mariner and Mystic | author = Weaver, Raymond M. (Raymond Melbourne) | ranking = 901 |
      Then fuzzy search for 'Aleksand' in 'books'
        | name = Eugene Oneguine [Onegin]: A Romance of Russian Life in Verse | author = Pushkin, Aleksandr Sergeevich | ranking = 826 |
      Then fuzzy search for 'Wuthring' in 'books'
        | name = Wuthering Heights | author = Brontë, Emily | ranking = 3 |
        | name = The Mayflower, January, 1905 | author = Various | ranking = 248 |

    @Interval
    Scenario: interval search books
      Then interval search for 'victor frankenstein' in 'books'
        | name = Frankenstein; or, the modern prometheus | author = Shelley, Mary Wollstonecraft | ranking = 1 | synopsis = "Frankenstein; Or, The Modern Prometheus" by Mary Wollstonecraft Shelley is a Gothic novel published in 1818. It tells the story of Victor Frankenstein, a young scientist who creates a living creature from assembled body parts in an unorthodox experiment. When the creature awakens, Victor flees in horror, abandoning his creation. The conscious being must navigate a world that fears him, learning language and seeking connection, only to face repeated rejection. Embittered and alone, the creature confronts his creator with a desperate request that will set both on a dark path of vengeance and tragedy. (This is an automatically generated summary.) |
      Then interval search for 'shelley mary' in 'books'
        | name = Frankenstein; or, the modern prometheus | author = Shelley, Mary Wollstonecraft | ranking = 1 |
      Then interval search for 'wuthering heights' in 'books'
        | name = Wuthering Heights | author = Brontë, Emily | ranking = 3 |

    @Span
    Scenario: span search books
      Then span search for 'victor frankenstein' in 'books'
        | name = Frankenstein; or, the modern prometheus | author = Shelley, Mary Wollstonecraft | ranking = 1 | synopsis = "Frankenstein; Or, The Modern Prometheus" by Mary Wollstonecraft Shelley is a Gothic novel published in 1818. It tells the story of Victor Frankenstein, a young scientist who creates a living creature from assembled body parts in an unorthodox experiment. When the creature awakens, Victor flees in horror, abandoning his creation. The conscious being must navigate a world that fears him, learning language and seeking connection, only to face repeated rejection. Embittered and alone, the creature confronts his creator with a desperate request that will set both on a dark path of vengeance and tragedy. (This is an automatically generated summary.) |
      Then span search for 'shelley mary' in 'books'
        | name = Frankenstein; or, the modern prometheus | author = Shelley, Mary Wollstonecraft | ranking = 1 |
      Then span search for 'wuthering heights' in 'books'
        | name = Wuthering Heights | author = Brontë, Emily | ranking = 3 |

    @Wildcard
    Scenario: wildcard search books
      Then wildcard search for 'Pequod' in 'books'
        | name = Moby Dick; Or, The Whale | author = Melville, Herman | ranking = 4 | synopsis = "Moby Dick; Or, The Whale" by Herman Melville is an epic novel published in 1851. Sailor Ishmael narrates the obsessive quest of Captain Ahab, who commands the whaling ship Pequod in pursuit of Moby Dick, a giant white sperm whale that destroyed his leg. Ahab's monomaniacal hunt for vengeance drives the ship and its diverse crew across the world's oceans, blending realistic whaling details with profound explorations of good, evil, fate, and human nature in this cornerstone of American literature. (This is an automatically generated summary.) |
      Then wildcard search for 'Shelley' in 'books'
        | name = Frankenstein; or, the modern prometheus | author = Shelley, Mary Wollstonecraft | ranking = 1 |
      Then wildcard search for 'Wuthering' in 'books'
        | name = Wuthering Heights | author = Brontë, Emily | ranking = 3 |

    @All
    Scenario: search books
      Then search for 'Pequod' in 'books'
        | name = Moby Dick; Or, The Whale | author = Melville, Herman | ranking = 4 | synopsis = "Moby Dick; Or, The Whale" by Herman Melville is an epic novel published in 1851. Sailor Ishmael narrates the obsessive quest of Captain Ahab, who commands the whaling ship Pequod in pursuit of Moby Dick, a giant white sperm whale that destroyed his leg. Ahab's monomaniacal hunt for vengeance drives the ship and its diverse crew across the world's oceans, blending realistic whaling details with profound explorations of good, evil, fate, and human nature in this cornerstone of American literature. (This is an automatically generated summary.) |
      Then search for 'Heathcliff' in 'books'
        | name = Wuthering Heights | author = Brontë, Emily | ranking = 3 |
      Then search for 'federalist' in 'books'
        | name = The Federalist Papers | author = Hamilton, Alexander | ranking = 102 |
      Then search for 'Trollope' in 'books'
        | name = Barchester Towers | author = Trollope, Anthony | ranking = 260 |

  Rule: Companies

    @Wildcard
    Scenario: wildcard search companies
      Then wildcard search for 'Cupertino' in 'companies'
        | name = Apple Inc. | ceo = Tim Cook | country = United States | rank = 3 |
      Then wildcard search for 'Jensen Huang' in 'companies'
        | name = Nvidia | ceo = Jensen Huang | country = United States | rank = 2 |
      Then wildcard search for 'Taiwan' in 'companies'
        | name = TSMC | ceo = C. C. Wei | country = Taiwan | rank = 9 |
      Then wildcard search for 'state-owned' in 'companies'
        | name = Saudi Aramco | ceo = Amin H. Al-Nasser | country = Saudi Arabia | rank = 1 |
      Then wildcard search for 'oil refining' in 'companies'
        | name = Saudi Aramco | ceo = Amin H. Al-Nasser | country = Saudi Arabia | rank = 1 |
      Then wildcard search for 'ExxonMobil' in 'companies'
        | name = ExxonMobil | ceo = Darren Woods | country = United States | rank = 14 |
      Then wildcard search for 'public finance' in 'companies'
        | name = U.S. Bancorp | ceo = Gunjan Kedia | country = United States | rank = 7 |
      Then wildcard search for 'schinese' in 'companies'
        | name = TSMC | ceo = C. C. Wei | country = Taiwan | rank = 9 | website = https://www.tsmc.com/schinese |

    @All
    Scenario: search companies
      Then search for 'Dhahran' in 'companies'
        | name = Saudi Aramco | ceo = Amin H. Al-Nasser | country = Saudi Arabia | rank = 1 |
      Then search for 'Satya' in 'companies'
        | name = Microsoft | ceo = Satya Nadella | country = United States | rank = 4 |
      Then search for 'schinese' in 'companies'
        | name = TSMC | ceo = C. C. Wei | country = Taiwan | rank = 9 | website = https://www.tsmc.com/schinese |
      Then search for 'Chinese multinational' in 'companies'
        | name = Alibaba Group | ceo = Joseph Tsai | country = People's Republic of China | rank = 13 |
      Then search for 'oil refining' in 'companies'
        | name = Saudi Aramco | ceo = Amin H. Al-Nasser | country = Saudi Arabia | rank = 1 |
      Then search for 'JPMorgan Chase' in 'companies'
        | name = JPMorgan Chase | ceo = Jamie Dimon | country = United States | rank = 11 |
      Then search for 'audiovisual' in 'companies'
        | name = Netflix, Inc. | ceo = Ted Sarandos | country = United States | rank = 39 |
      Then search for 'Norway' in 'companies'
        | name = Equinor ASA | ceo = Anders Opedal | country = Norway | rank = 53 |

  Rule: Movies

    @Fuzzy
    Scenario: fuzzy search movies
      Then fuzzy search for 'uxoricyde' in 'movies'
        | name = The Shawshank Redemption | director = Frank Darabont | ranking = 1 | release_year = 1994 | synopsis = A banker convicted of uxoricide forms a friendship over a quarter century with a hardened convict, while maintaining his innocence and trying to remain hopeful through simple compassion. |
      Then fuzzy search for 'Jonatan' in 'movies'
        | name = The Silence of the Lambs | director = Jonathan Demme | ranking = 19 | release_year = 1991 |
      Then fuzzy search for 'Incepton' in 'movies'
        | name = Inception | director = Christopher Nolan | ranking = 3 | release_year = 2010 |

    @Interval
    Scenario: interval search movies
      Then interval search for 'hopeful compassion' in 'movies'
        | name = The Shawshank Redemption | director = Frank Darabont | ranking = 1 | release_year = 1994 | synopsis = A banker convicted of uxoricide forms a friendship over a quarter century with a hardened convict, while maintaining his innocence and trying to remain hopeful through simple compassion. |
      Then interval search for 'jonathan demme' in 'movies'
        | name = The Silence of the Lambs | director = Jonathan Demme | ranking = 19 | release_year = 1991 |
      Then interval search for 'forrest gump' in 'movies'
        | name = Forrest Gump | director = Robert Zemeckis | ranking = 6 | release_year = 1994 |

    @Span
    Scenario: span search movies
      Then span search for 'hopeful compassion' in 'movies'
        | name = The Shawshank Redemption | director = Frank Darabont | ranking = 1 | release_year = 1994 | synopsis = A banker convicted of uxoricide forms a friendship over a quarter century with a hardened convict, while maintaining his innocence and trying to remain hopeful through simple compassion. |
      Then span search for 'jonathan demme' in 'movies'
        | name = The Silence of the Lambs | director = Jonathan Demme | ranking = 19 | release_year = 1991 |
      Then span search for 'forrest gump' in 'movies'
        | name = Forrest Gump | director = Robert Zemeckis | ranking = 6 | release_year = 1994 |

    @Wildcard
    Scenario: wildcard search movies
      Then wildcard search for 'Scottish' in 'movies'
        | name = Braveheart | director = Mel Gibson | ranking = 66 | release_year = 1995 | synopsis = Scottish warrior William Wallace leads his countrymen in a rebellion to free his homeland from the tyranny of King Edward I of England. |
      Then wildcard search for 'Demme' in 'movies'
        | name = The Silence of the Lambs | director = Jonathan Demme | ranking = 19 | release_year = 1991 |
      Then wildcard search for 'Inception' in 'movies'
        | name = Inception | director = Christopher Nolan | ranking = 3 | release_year = 2010 |

    @All
    Scenario: search movies
      Then search for 'imprisoned' in 'movies'
        | name = Oldboy         | director = Park Chan-wook | ranking = 203 | release_year = 2003 | synopsis = After being kidnapped and imprisoned for fifteen years, Oh Dae-Su is released, only to find that he must track down his captor in five days. |
        | name = Thor: Ragnarok | director = Taika Waititi  | ranking = 122 | release_year = 2017 | synopsis = Imprisoned on the planet Sakaar, Thor must race against time to return to Asgard and stop Ragnarök, the destruction of his world, at the hands of the powerful and ruthless villain Hela. |
      Then search for 'Demme' in 'movies'
        | name = The Silence of the Lambs | director = Jonathan Demme | ranking = 19 | release_year = 1991 |
      Then search for 'Inception' in 'movies'
        | name = Inception | director = Christopher Nolan | ranking = 3 | release_year = 2010 |

  Rule: Music

    @Wildcard
    Scenario: wildcard search music
      Then wildcard search for 'Enola' in 'music'
        | name = Nuclear Attack | band = Sabaton | album = Attero Dominatus | release_year = 2000 | track_number = 2 |
      Then wildcard search for 'Bismarck' in 'music'
        | name = Bismarck | band = Sabaton | album = Bismarck - Single | release_year = 2019 | track_number = 1 |
      Then wildcard search for 'Defence of Moscow' in 'music'
        | name = Defence of Moscow | band = Sabaton | album = Defence of Moscow - Single | release_year = 2021 | track_number = 1 |

    @All
    Scenario: search music
      Then search for 'Versailles' in 'music'
        | name = Rise of Evil | band = Sabaton | album = Attero Dominatus | release_year = 2000 | track_number = 3 |
      Then search for 'kriegsmarine' in 'music'
        | name = Bismarck | band = Sabaton | album = Bismarck - Single | release_year = 2019 | track_number = 1 |
      Then search for 'magadan' in 'music'
        | name = Defence of Moscow | band = Sabaton | album = Defence of Moscow - Single | release_year = 2021 | track_number = 1 |
      Then search for 'Screaming Eagles' in 'music'
        | name = Screaming Eagles | band = Sabaton | album = Coat of Arms | release_year = 2010 | track_number = 4 |
      Then search for 'gracias' in 'music'
        | name = Chiquitita (Spanish Version) | band = ABBA | album = Gracias por la Música (Deluxe Edition) | release_year = 1980 | track_number = 10 |

  Rule: People

    @Wildcard
    Scenario: wildcard search people
      Then wildcard search for 'Lumbini' in 'people'
        | name = Gautama | surname = Buddha | ranking = 2 | reasons_for_being_famous = Philosopher born in Lumbini, Nepal. |
      Then wildcard search for 'Aristotle' in 'people'
        | name = Aristotle | ranking = 12 | reasons_for_being_famous = Philosopher born in Stagira (ancient city), Greece. |
      Then wildcard search for 'Newton' in 'people'
        | name = Isaac | surname = Newton | ranking = 3 | reasons_for_being_famous = Physicist born in Woolsthorpe-by-Colsterworth, United Kingdom. |

    @All
    Scenario: search people
      Then search for 'Woolsthorpe' in 'people'
        | name = Isaac | surname = Newton | ranking = 3 | reasons_for_being_famous = Physicist born in Woolsthorpe-by-Colsterworth, United Kingdom. |
      Then search for 'Cleopatra' in 'people'
        | name = Cleopatra | ranking = 6 | reasons_for_being_famous = Politician born in Alexandria, Egypt. |
      Then search for 'Trump' in 'people'
        | name = Donald | surname = Trump | ranking = 4 | reasons_for_being_famous = Politician born in Queens, United States. |
