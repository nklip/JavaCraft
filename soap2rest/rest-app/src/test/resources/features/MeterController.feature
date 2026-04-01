Feature: MeterResource

  Rule: Sync meter resource behavior

    @Sync @Meter
    Scenario: create a simple new meter for account 1
      Given the account 1 doesn't have any metrics
      And account 1 doesn't have meters
      When account 1 submits a new meter with manufacturer 'Landis + Gyr'
      Then account 1 has meters list size 1
      And the last created meter for account 1 has manufacturer 'Landis + Gyr'
      And account 1 can get the last created meter

    @Sync @Meter
    Scenario: create and update a meter for account 1
      Given the account 1 doesn't have any metrics
      And account 1 doesn't have meters
      When account 1 submits a new meter with manufacturer 'Siemens'
      And account 1 updates the last created meter with manufacturer 'Itron'
      Then account 1 has meters list size 1
      And the last created meter for account 1 has manufacturer 'Itron'
      And account 1 can get the last created meter

    @Sync @Meter
    Scenario: account isolation and delete behavior for meters
      Given the account 1 doesn't have any metrics
      And the account 2 doesn't have any metrics
      And account 1 doesn't have meters
      And account 2 doesn't have meters

      When account 1 submits a new meter with manufacturer 'Landis + Gyr'
      And account 1 submits a new meter with manufacturer 'Siemens'
      And account 2 submits a new meter with manufacturer 'Itron'
      And account 2 submits a new meter with manufacturer 'Osaki Electric'
      Then account 1 has meters list size 2
      And account 2 has meters list size 2
      And account 2 cannot access account 1 first created meter

      When account 2 deletes the first created meter
      Then account 2 has meters list size 1

      When account 1 deletes all meters
      Then account 1 has meters list size 0
