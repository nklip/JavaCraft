Feature: BMI service

  Scenario: A person gets a BMI and category from metric measurements
    When a person calculates their BMI from '75' kg and '1.75' metres
    And they check their BMI category
    Then their BMI should be '24.49'
    And their BMI category should be 'Normal range as your BMI is between 18.50 and 24.99'

  Scenario: A person gets a BMI from imperial measurements
    When a person calculates their BMI from '150' lbs and '65' inches
    Then their BMI should be '24.96'

  Scenario Outline: A person with BMI <BMI> gets the corresponding category
    Given a person has a BMI of '<BMI>'
    When they check their BMI category
    Then their BMI category should be '<category>'

    Examples:
      | BMI   | category                                                 |
      | 0.01  | Severe thinness as your BMI is less than 16.00           |
      | 15.99 | Severe thinness as your BMI is less than 16.00           |
      | 16.00 | Moderate thinness as your BMI is between 16.00 and 16.99 |
      | 16.99 | Moderate thinness as your BMI is between 16.00 and 16.99 |
      | 17.00 | Mild thinness as your BMI is between 17.00 and 18.49     |
      | 18.49 | Mild thinness as your BMI is between 17.00 and 18.49     |
      | 18.50 | Normal range as your BMI is between 18.50 and 24.99      |
      | 22.00 | Normal range as your BMI is between 18.50 and 24.99      |
      | 24.99 | Normal range as your BMI is between 18.50 and 24.99      |
      | 25.00 | Pre-obese as your BMI is between 25.00 and 29.99         |
      | 29.99 | Pre-obese as your BMI is between 25.00 and 29.99         |
      | 30.00 | Obese class I as your BMI is between 30.00 and 34.99     |
      | 34.99 | Obese class I as your BMI is between 30.00 and 34.99     |
      | 35.00 | Obese class II as your BMI is between 35.00 and 39.99    |
      | 39.99 | Obese class II as your BMI is between 35.00 and 39.99    |
      | 40.00 | Obese class III as your BMI is 40.00 or more             |
