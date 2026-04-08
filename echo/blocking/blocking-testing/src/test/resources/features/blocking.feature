Feature: Blocking server behavior

  Rule: Simple connection flows

    @Virtual
    Scenario: [Virtual] One client can connect, check the current client count, and disconnect
      Given the virtual server is running on port 8100
      When virtual client "Nikita" connects on port 8100
      Then client "Nikita" sends "stats" and receives "Simultaneously connected clients: 1"
      And client "Nikita" disconnects with goodbye

    @Platform
    Scenario: [Platform] One client can connect, check the current client count, and disconnect
      Given the virtual server is running on port 8200
      When platform client "Nikita" connects on port 8200
      Then client "Nikita" sends "stats" and receives "Simultaneously connected clients: 1"
      And client "Nikita" disconnects with goodbye

    @Virtual
    Scenario: [Virtual] Several clients see the correct number of connected users
      Given the virtual server is running on port 8105
      When virtual client "Nikita" connects on port 8105
      And virtual client "Ava" connects on port 8105
      And virtual client "Alyssa" connects on port 8105
      Then client "Nikita" sends "stats" and receives "Simultaneously connected clients: 3"
      And client "Ava" sends "stats" and receives "Simultaneously connected clients: 3"
      And client "Alyssa" sends "stats" and receives "Simultaneously connected clients: 3"
      And client "Nikita" sends "Hello World!" and receives "Did you say 'Hello World!'?"
      And client "Ava" sends "Java" and receives "Did you say 'Java'?"
      And client "Alyssa" sends "mimimi" and receives "Did you say 'mimimi'?"
      And client "Nikita" disconnects with goodbye
      And client "Ava" disconnects with goodbye
      And client "Alyssa" disconnects with goodbye

    @Platform
    Scenario: [Platform] Several clients see the correct number of connected users
      Given the virtual server is running on port 8205
      When platform client "Nikita" connects on port 8205
      And platform client "Ava" connects on port 8205
      And platform client "Alyssa" connects on port 8205
      Then client "Nikita" sends "stats" and receives "Simultaneously connected clients: 3"
      And client "Ava" sends "stats" and receives "Simultaneously connected clients: 3"
      And client "Alyssa" sends "stats" and receives "Simultaneously connected clients: 3"
      And client "Nikita" sends "Hello World!" and receives "Did you say 'Hello World!'?"
      And client "Ava" sends "Java" and receives "Did you say 'Java'?"
      And client "Alyssa" sends "mimimi" and receives "Did you say 'mimimi'?"
      And client "Nikita" disconnects with goodbye
      And client "Ava" disconnects with goodbye
      And client "Alyssa" disconnects with goodbye

    @Virtual
    Scenario: [Virtual] A client gets an echo response for regular text
      Given the virtual server is running on port 8110
      When virtual client "Echo" connects on port 8110
      Then client "Echo" sends "hello world" and receives "Did you say 'hello world'?"
      And client "Echo" disconnects with goodbye

    @Platform
    Scenario: [Platform] A client gets an echo response for regular text
      Given the virtual server is running on port 8210
      When platform client "Echo" connects on port 8210
      Then client "Echo" sends "hello world" and receives "Did you say 'hello world'?"
      And client "Echo" disconnects with goodbye

    @Virtual
    Scenario: [Virtual] One client can send several messages on the same connection
      Given the virtual server is running on port 8115
      When virtual client "Repeat" connects on port 8115
      Then client "Repeat" sends "first" and receives "Did you say 'first'?"
      And client "Repeat" sends "StAtS" and receives "Simultaneously connected clients: 1"
      And client "Repeat" sends "second" and receives "Did you say 'second'?"
      And client "Repeat" disconnects with goodbye

    @Platform
    Scenario: [Platform] One client can send several messages on the same connection
      Given the virtual server is running on port 8215
      When platform client "Repeat" connects on port 8215
      Then client "Repeat" sends "first" and receives "Did you say 'first'?"
      And client "Repeat" sends "StAtS" and receives "Simultaneously connected clients: 1"
      And client "Repeat" sends "second" and receives "Did you say 'second'?"
      And client "Repeat" disconnects with goodbye

    @Virtual
    Scenario: [Virtual] The connected client count drops after one client leaves
      Given the virtual server is running on port 8120
      When virtual client "Primary" connects on port 8120
      And virtual client "Secondary" connects on port 8120
      Then client "Primary" sends "stats" and receives "Simultaneously connected clients: 2"
      And client "Secondary" sends "BYE" and receives "Have a good day!"
      And client "Primary" sends "stats" and receives "Simultaneously connected clients: 1"
      And client "Secondary" socket is closed
      And client "Primary" disconnects with goodbye

    @Platform
    Scenario: [Platform] The connected client count drops after one client leaves
      Given the virtual server is running on port 8220
      When platform client "Primary" connects on port 8220
      And platform client "Secondary" connects on port 8220
      Then client "Primary" sends "stats" and receives "Simultaneously connected clients: 2"
      And client "Secondary" sends "BYE" and receives "Have a good day!"
      And client "Primary" sends "stats" and receives "Simultaneously connected clients: 1"
      And client "Secondary" socket is closed
      And client "Primary" disconnects with goodbye

    @Virtual
    Scenario: [Virtual] A client socket closes after goodbye
      Given the virtual server is running on port 8122
      When virtual client "Closer" connects on port 8122
      Then client "Closer" sends "bye" and receives "Have a good day!"
      And client "Closer" socket is closed

    @Platform
    Scenario: [Platform] A client socket closes after goodbye
      Given the virtual server is running on port 8222
      When platform client "Closer" connects on port 8222
      Then client "Closer" sends "bye" and receives "Have a good day!"
      And client "Closer" socket is closed

    @Virtual
    Scenario: [Virtual] The connected client count recovers after a client says bye
      Given the virtual server is running on port 8125
      When virtual client "First" connects on port 8125
      And virtual client "Second" connects on port 8125
      Then client "First" sends "stats" and receives "Simultaneously connected clients: 2"
      And client "Second" sends "bye" and receives "Have a good day!"
      When virtual client "Replacement" connects on port 8125
      Then client "Replacement" sends "stats" and receives "Simultaneously connected clients: 2"
      And client "Second" socket is closed
      And client "First" disconnects with goodbye
      And client "Replacement" disconnects with goodbye

    @Platform
    Scenario: [Platform] The connected client count recovers after a client says bye
      Given the virtual server is running on port 8225
      When platform client "First" connects on port 8225
      And platform client "Second" connects on port 8225
      Then client "First" sends "stats" and receives "Simultaneously connected clients: 2"
      And client "Second" sends "bye" and receives "Have a good day!"
      When platform client "Replacement" connects on port 8225
      Then client "Replacement" sends "stats" and receives "Simultaneously connected clients: 2"
      And client "Second" socket is closed
      And client "First" disconnects with goodbye
      And client "Replacement" disconnects with goodbye

  Rule: Edge case and message format behavior

    @Virtual
    Scenario: [Virtual] An empty message gets guidance
      Given the virtual server is running on port 8130
      When virtual client "Empty" connects on port 8130
      Then client "Empty" sends "" and receives "Please type something."
      And client "Empty" disconnects with goodbye

    @Platform
    Scenario: [Platform] An empty message gets guidance
      Given the virtual server is running on port 8230
      When platform client "Empty" connects on port 8230
      Then client "Empty" sends "" and receives "Please type something."
      And client "Empty" disconnects with goodbye

    @Virtual
    Scenario: [Virtual] A client can continue after sending an empty message
      Given the virtual server is running on port 8135
      When virtual client "Recover" connects on port 8135
      Then client "Recover" sends "" and receives "Please type something."
      And client "Recover" sends "stats" and receives "Simultaneously connected clients: 1"
      And client "Recover" sends "still here" and receives "Did you say 'still here'?"
      And client "Recover" disconnects with goodbye

    @Platform
    Scenario: [Platform] A client can continue after sending an empty message
      Given the virtual server is running on port 8235
      When platform client "Recover" connects on port 8235
      Then client "Recover" sends "" and receives "Please type something."
      And client "Recover" sends "stats" and receives "Simultaneously connected clients: 1"
      And client "Recover" sends "still here" and receives "Did you say 'still here'?"
      And client "Recover" disconnects with goodbye

    @Virtual
    Scenario: [Virtual] Surrounding spaces are preserved while line endings are trimmed
      Given the virtual server is running on port 8140
      When virtual client "Spaces" connects on port 8140
      Then client "Spaces" sends "  keep surrounding spaces  " and receives "Did you say '  keep surrounding spaces  '?"
      And client "Spaces" disconnects with goodbye

    @Platform
    Scenario: [Platform] Surrounding spaces are preserved while line endings are trimmed
      Given the virtual server is running on port 8240
      When platform client "Spaces" connects on port 8240
      Then client "Spaces" sends "  keep surrounding spaces  " and receives "Did you say '  keep surrounding spaces  '?"
      And client "Spaces" disconnects with goodbye

    @Virtual
    Scenario: [Virtual] Tabs and backslashes are preserved in the echo response
      Given the virtual server is running on port 8145
      When virtual client "Escapes" connects on port 8145
      Then client "Escapes" sends escaped message "left\tright\\tail" and receives escaped response "Did you say 'left\tright\\tail'?"
      And client "Escapes" disconnects with goodbye

    @Platform
    Scenario: [Platform] Tabs and backslashes are preserved in the echo response
      Given the virtual server is running on port 8245
      When platform client "Escapes" connects on port 8245
      Then client "Escapes" sends escaped message "left\tright\\tail" and receives escaped response "Did you say 'left\tright\\tail'?"
      And client "Escapes" disconnects with goodbye

  Rule: High load behavior

    @Virtual
    Scenario: [Virtual] The server handles 100 clients sending 10,000 messages from separate threads
      Given the virtual server is running on port 8150
      When 100 virtual clients with prefix "VirtualLoad" connect on port 8150
      Then client "VirtualLoad-001" sends "stats" and receives "Simultaneously connected clients: 100"
      When 100 clients with prefix "VirtualLoad" each send 100 echo messages from their own thread with a random delay between 10 and 50 milliseconds
      Then 100 clients with prefix "VirtualLoad" disconnect with goodbye

    @Platform
    Scenario: [Platform] The server handles 100 clients sending 10,000 messages from separate threads
      Given the virtual server is running on port 8250
      When 100 platform clients with prefix "PlatformLoad" connect on port 8250
      Then client "PlatformLoad-001" sends "stats" and receives "Simultaneously connected clients: 100"
      When 100 clients with prefix "PlatformLoad" each send 100 echo messages from their own thread with a random delay between 10 and 50 milliseconds
      Then 100 clients with prefix "PlatformLoad" disconnect with goodbye
