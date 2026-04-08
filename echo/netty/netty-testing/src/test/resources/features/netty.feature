Feature: Netty server behavior

  Rule: Simple connection flows

    Scenario: One client can connect, check the current client count, and disconnect
      Given the Netty server is running on port 8030
      When client "Nikita" connects to the Netty server on port 8030
      Then client "Nikita" sends "stats" and receives "Simultaneously connected clients: 1"
      And client "Nikita" disconnects with goodbye

    Scenario: Several clients see the correct number of connected users
      Given the Netty server is running on port 8035
      When client "Nikita" connects to the Netty server on port 8035
      And client "Ava" connects to the Netty server on port 8035
      And client "Alyssa" connects to the Netty server on port 8035
      Then client "Nikita" sends "stats" and receives "Simultaneously connected clients: 3"
      And client "Ava" sends "stats" and receives "Simultaneously connected clients: 3"
      And client "Alyssa" sends "stats" and receives "Simultaneously connected clients: 3"
      And client "Nikita" sends "Hello World!" and receives "Did you say 'Hello World!'?"
      And client "Ava" sends "Java" and receives "Did you say 'Java'?"
      And client "Alyssa" sends "mimimi" and receives "Did you say 'mimimi'?"
      And client "Nikita" disconnects with goodbye
      And client "Ava" disconnects with goodbye
      And client "Alyssa" disconnects with goodbye

    Scenario: A client gets an echo response for regular text
      Given the Netty server is running on port 8038
      When client "Echo" connects to the Netty server on port 8038
      Then client "Echo" sends "hello world" and receives "Did you say 'hello world'?"
      And client "Echo" disconnects with goodbye

    Scenario: One client can send several messages on the same connection
      Given the Netty server is running on port 8042
      When client "Repeat" connects to the Netty server on port 8042
      Then client "Repeat" sends "first" and receives "Did you say 'first'?"
      And client "Repeat" sends "StAtS" and receives "Simultaneously connected clients: 1"
      And client "Repeat" sends "second" and receives "Did you say 'second'?"
      And client "Repeat" disconnects with goodbye

    Scenario: The connected client count drops after one client leaves
      Given the Netty server is running on port 8045
      When client "Primary" connects to the Netty server on port 8045
      And client "Secondary" connects to the Netty server on port 8045
      Then client "Primary" sends "stats" and receives "Simultaneously connected clients: 2"
      And client "Secondary" sends "BYE" and receives "Have a good day!"
      And client "Primary" sends "stats" and receives "Simultaneously connected clients: 1"
      And client "Secondary" socket is closed
      And client "Primary" disconnects with goodbye

    Scenario: A client socket closes after goodbye
      Given the Netty server is running on port 8048
      When client "Closer" connects to the Netty server on port 8048
      Then client "Closer" sends "bye" and receives "Have a good day!"
      And client "Closer" socket is closed

    Scenario: The connected client count recovers after a client says bye
      Given the Netty server is running on port 8050
      When client "First" connects to the Netty server on port 8050
      And client "Second" connects to the Netty server on port 8050
      Then client "First" sends "stats" and receives "Simultaneously connected clients: 2"
      And client "Second" sends "bye" and receives "Have a good day!"
      When client "Replacement" connects to the Netty server on port 8050
      Then client "Replacement" sends "stats" and receives "Simultaneously connected clients: 2"
      And client "Second" socket is closed
      And client "First" disconnects with goodbye
      And client "Replacement" disconnects with goodbye

  Rule: Edge case and message format behavior

    Scenario: An empty message gets guidance
      Given the Netty server is running on port 8055
      When client "Empty" connects to the Netty server on port 8055
      Then client "Empty" sends "" and receives "Please type something."
      And client "Empty" disconnects with goodbye

    Scenario: A client can continue after sending an empty message
      Given the Netty server is running on port 8060
      When client "Recover" connects to the Netty server on port 8060
      Then client "Recover" sends "" and receives "Please type something."
      And client "Recover" sends "stats" and receives "Simultaneously connected clients: 1"
      And client "Recover" sends "still here" and receives "Did you say 'still here'?"
      And client "Recover" disconnects with goodbye

    Scenario: Surrounding spaces are preserved in the echo response
      Given the Netty server is running on port 8065
      When client "Spaces" connects to the Netty server on port 8065
      Then client "Spaces" sends "  keep surrounding spaces  " and receives "Did you say '  keep surrounding spaces  '?"
      And client "Spaces" disconnects with goodbye

    Scenario: Tabs and backslashes are preserved in the echo response
      Given the Netty server is running on port 8070
      When client "Escapes" connects to the Netty server on port 8070
      Then client "Escapes" sends escaped message "left\tright\\tail" and receives escaped response "Did you say 'left\tright\\tail'?"
      And client "Escapes" disconnects with goodbye

  Rule: High load behavior

    Scenario: The server handles 100 clients sending 10,000 messages from separate threads
      Given the Netty server is running on port 8095
      When 100 clients with prefix "Load" connect to the Netty server on port 8095
      Then client "Load-001" sends "stats" and receives "Simultaneously connected clients: 100"
      When 100 clients with prefix "Load" each send 100 echo messages from their own thread with a random delay between 10 and 50 milliseconds
      Then 100 clients with prefix "Load" disconnect with goodbye
