Feature: Investment Asset API
  Authenticated investors can browse and search active assets.

  Background:
    Given the controlled asset catalog contains:
      | symbol | name           | type   | price  | status   |
      | BDDSTK | Demo Equity    | STOCK  | 100.00 | ACTIVE   |
      | BDDETF | Demo Fund      | ETF    | 50.00  | ACTIVE   |
      | BDDCRY | Demo Coin      | CRYPTO | 25.00  | ACTIVE   |
      | BDDOFF | Retired Equity | STOCK  | 10.00  | INACTIVE |

  Scenario: View all active assets
    Given an authenticated investor
    When the investor requests GET "/assets"
    Then the response status should be 200
    And the response should be a JSON array of valid asset objects
    And the returned symbols should be exactly "BDDSTK,BDDETF,BDDCRY"
    And every returned asset should have status "ACTIVE"

  Scenario: Search assets by symbol
    Given an authenticated investor
    When the investor requests assets with search "bddstk"
    Then the response status should be 200
    And the returned symbols should be exactly "BDDSTK"
    And every returned asset should have status "ACTIVE"

  Scenario: Filter assets by type
    Given an authenticated investor
    When the investor requests assets with type "STOCK"
    Then the response status should be 200
    And the returned symbols should be exactly "BDDSTK"
    And every returned asset should have type "STOCK"
    And every returned asset should have status "ACTIVE"

  Scenario: Search for a nonexistent asset
    Given an authenticated investor
    When the investor requests assets with search "BDD_DOES_NOT_EXIST"
    Then the response status should be 200
    And the response should be an empty JSON array

  Scenario: Access assets without authentication
    Given an unauthenticated visitor
    When the visitor requests GET "/assets"
    Then the response status should be 401
