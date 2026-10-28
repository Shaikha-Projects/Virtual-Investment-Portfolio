# Virtual Investment Portfolio System

A full-stack **virtual investment simulation platform** built with **Java 17, Spring Boot, PostgreSQL, React, and Vite**. Investors start with **$100,000 in virtual funds** and can buy and sell simulated assets, place limit orders, and monitor portfolio performance without using real money.

Project 3 builds on the original investment application with concurrent order execution, scheduled market simulation, database locking, asynchronous notifications, stronger rate limiting, and expanded portfolio analytics.

## Table of Contents

- [Project Overview](#project-overview)
- [Features at a Glance](#features-at-a-glance)
- [Technology Stack](#technology-stack)
- [Architecture and Database](#architecture-and-database)
- [Authentication and User Management](#authentication-and-user-management)
- [Investment Management](#investment-management)
- [Market Simulation and Asset Price History](#market-simulation-and-asset-price-history)
- [Portfolio Analytics and Performance History](#portfolio-analytics-and-performance-history)
- [Investor Dashboard and Administration](#investor-dashboard-and-administration)
- [Concurrency and Multithreading](#concurrency-and-multithreading)
- [Race Conditions and Thread Safety](#race-conditions-and-thread-safety)
- [API Rate Limiting](#api-rate-limiting)
- [Real-Time and Email Notifications](#real-time-and-email-notifications)
- [Audit Logging](#audit-logging)
- [Testing and Verification](#testing-and-verification)
- [Development Approach and Planning](#development-approach-and-planning)
- [Entity Relationship Diagram](#entity-relationship-diagram)
- [API Documentation](#api-documentation)
- [Installation and Configuration](#installation-and-configuration)
- [Challenges and Limitations](#challenges-and-limitations)
- [Future Improvements](#future-improvements)
- [Credits and Resources](#credits-and-resources)

## Project Overview

The Virtual Investment Portfolio System provides a safe learning environment for trading with virtual money. After email verification, each investor receives a virtual portfolio with an initial balance of **$100,000**. Investors can explore assets, trade, track holdings and orders, and examine financial performance. Administrators manage assets, users, and audit information.

The backend exposes secured REST endpoints; React provides the investor and administrator interfaces; PostgreSQL stores accounts, trades, orders, market history, and portfolio history. This is an **educational simulator**, not a real-money brokerage or a source of live exchange prices.

## Features at a Glance

| Area | Features |
|---|---|
| Authentication | Registration, email verification, JWT login, password change/reset, profile management, USER/ADMIN authorization |
| Trading | Direct BUY/SELL, limit BUY/SELL, cash/share reservations, cancel pending orders, transaction history |
| Market | Simulated price updates, asset searches/filters, price history and charts, watchlist |
| Portfolio | Total value, available cash, invested cost basis, realized/unrealized profit or loss, overall return, historical snapshots |
| Real time | SSE notifications, automatic order-status refresh, HTML transaction-confirmation emails |
| Administration | Asset management, soft user deactivation, paginated audit logs |
| Reliability | Database transactions, pessimistic locks, managed thread pools, thread-safe task tracking, API rate limiting |
| Documentation | Swagger/OpenAPI, Jira user stories, ERD, automated tests |

## Technology Stack

| Layer | Technologies |
|---|---|
| Backend | Java 17, Spring Boot, Spring Web, Spring Security, Spring Data JPA, Hibernate, Jakarta Bean Validation |
| Database | PostgreSQL |
| Authentication and API protection | JWT, Spring Security, Bucket4j |
| Concurrency and messaging | Spring `@Scheduled`, `ThreadPoolTaskExecutor`, `@Async`, `@Transactional`, SSE, Spring Mail |
| Frontend | React, Vite, JavaScript, HTML, CSS |
| Documentation and testing | Swagger/OpenAPI, JUnit, Mockito, PostgreSQL integration tests, Postman |
| Development | Maven, Git, GitHub, Jira |

## Architecture and Database

The application follows a **layered architecture**:

- **Controllers:** receive requests and return REST responses.
- **Services:** implement trading rules, authentication, scheduled tasks, and portfolio calculations.
- **Repositories:** interact with PostgreSQL through Spring Data JPA.
- **Models:** represent persisted data and relationships.
- **DTOs:** define request and response objects without exposing entities directly.
- **Security and filters:** enforce JWT authentication, roles, and request limits.
- **Exception handling:** supplies consistent HTTP error responses.

The database currently contains **12 entities**:

`User`, `Portfolio`, `Asset`, `Holding`, `Transaction`, `Watchlist`, `EmailVerificationToken`, `PasswordResetToken`, `AuditLog`, `InvestmentOrder`, `PriceHistory`, and `PortfolioSnapshot`.

One portfolio belongs to an investor and connects to holdings, completed transactions, investment orders, and historical portfolio snapshots. An asset connects to holdings, transactions, orders, and recorded price history. Order reservations are tracked using reserved cash and reserved share quantities.

## Authentication and User Management

Investors register and verify their email address before using the platform. A verified account receives its virtual starting balance. Spring Security protects API endpoints through JWT authentication and `USER`/`ADMIN` roles. The platform also provides password change/reset, profile editing, profile-picture upload, and account deactivation. User status is checked during authenticated requests so that deactivated users cannot continue using previously issued tokens.

## Investment Management

### Direct BUY and SELL

A direct **BUY** checks that the asset is active and that available cash is sufficient after subtracting cash reserved for pending orders. On success, the application reduces the cash balance, creates or updates the holding, records a completed transaction, writes an audit log, and publishes confirmation events.

A direct **SELL** checks that the investor owns enough *available* shares after subtracting shares reserved for pending SELL orders. On success, holdings decrease, cash increases, a completed transaction is stored, and confirmation events are published. Both workflows run inside database transactions.

### BUY and SELL Limit Orders

Investors may place pending orders with an asset, quantity, type, and target limit price.

- **BUY limit:** the required cash (`quantity × limit price`) is reserved when placing the order. An eligible BUY fills when the current simulated price is **at or below** the limit price. The actual execution cost is deducted and the reserved amount is released.
- **SELL limit:** the requested shares are reserved when placing the order. An eligible SELL fills when the current simulated price is **at or above** the limit price. Sold shares are deducted and cash proceeds are credited.
- **Cancellation:** an investor may cancel a `PENDING` order, releasing its reserved cash or shares.

Orders move from `PENDING` to `FILLED` or `CANCELLED`. The background executor periodically evaluates pending orders; each execution rechecks order status under a database lock before changing balances. The frontend displays order history and updates filled orders after execution notifications. The SELL form shows owned, reserved, and available shares and supports a **Max** quantity option.

**Order lifecycle:** `Place order → Reserve funds/shares → PENDING → Price condition met → FILLED`, or `PENDING → Cancel → Release reservation → CANCELLED`.

## Market Simulation and Asset Price History

Because this project does not use a live stock-exchange API, it includes a simulated market engine. Approximately every **10 seconds**, active assets are scheduled for independent price updates. Each update applies a bounded random movement of approximately **±0.5%** to the previous price, protects the asset record with a pessimistic write lock, and saves a `PriceHistory` record.

Investors can view simulated price changes in an asset chart. These are simulated prices and should not be interpreted as live market quotes.

## Portfolio Analytics and Performance History

### Portfolio Metrics

The dashboard calculates current portfolio values from cash, holdings, purchase cost, and simulated prices.

| Metric | Calculation |
|---|---|
| Holdings market value | Sum of `holding quantity × current asset price` |
| Invested cost basis | Sum of `holding quantity × average purchase price` |
| Total portfolio value | Cash balance + holdings market value |
| Unrealized P/L | Holdings market value − invested cost basis |
| Overall gain/loss | Total portfolio value − initial $100,000 |
| Overall return (%) | Overall gain/loss ÷ initial $100,000 × 100 |
| Realized P/L for a SELL | Sale proceeds − cost basis of shares sold |

Overall-return calculations assume the virtual starting capital remains $100,000, with no later deposits, withdrawals, or account-balance resets.

### Realized Profit/Loss

For **new completed SELL transactions**, the backend calculates the cost basis from the holding's average purchase price **before** reducing its quantity. The `Transaction` record stores `costBasis` and `realizedGainLoss`. The portfolio-performance API sums recorded realized gains and losses from completed SELL transactions.

**Historical limitation:** SELL transactions made before these columns were introduced may have null cost-basis and realized P/L values. The currently displayed realized P/L total therefore covers recorded transactions only and may not equal the investor's full lifetime realized result until older transactions are backfilled.

### Portfolio Performance History

The `PortfolioSnapshot` entity records an investor's total portfolio value and a timestamp. `PortfolioSnapshotService` calculates cash plus current holdings value for each portfolio; a scheduled job saves snapshots approximately **every five minutes**. Snapshots are stored in PostgreSQL and can be retrieved through the authenticated endpoint **`GET /portfolio/history`**.

The React **Portfolio Performance** chart uses those records to display portfolio value over time, with **1D, 1W, 1M, and ALL** filters. It handles closely spaced snapshots caused by application restarts and does not invent missing historical data.

**Data flow:** `Portfolio + holdings → Calculate total value → Scheduled snapshot → PostgreSQL → GET /portfolio/history → React chart`.

Snapshot history starts when recording was enabled; it cannot show earlier values that were never saved. The chart records periodic observations, not every market tick.

## Investor Dashboard and Administration

### Investor Dashboard

The React dashboard brings together the investor's total portfolio value, overall return, available cash, holdings market value, unrealized and recorded realized P/L, historical portfolio chart, asset allocation, holdings breakdown, and recent transactions. Investors can navigate to assets, holdings, orders, watchlist, transaction history, and profile management.

### Administrator Workspace

Administrators can manage available assets, deactivate user accounts without deleting historical records, and review important audit activities. Administrative routes are restricted by role.



## Concurrency and Multithreading

Project 3 introduces multithreading to allow the application to process multiple tasks concurrently while maintaining data consistency.

The application uses three separate Spring-managed thread pools.

### 1. Order Execution Thread Pool

The `orderExecutor` thread pool handles automatic execution of pending BUY and SELL limit orders.

The system periodically checks pending orders to determine whether their limit prices have been reached.

When an order becomes eligible for execution, the scheduler submits the order to the thread pool.

Each worker processes an order independently using a database transaction.

The thread pool configuration includes:

- Core pool size: 3
- Maximum pool size: 5
- Queue capacity: 10

The order scheduler runs approximately every 10 seconds.

### 2. Market Price Update Thread Pool

The `marketExecutor` thread pool handles simulated market price updates.

The application periodically retrieves active assets and submits their IDs to the thread pool.

Each worker independently updates an asset's price and records the new value in the price history table.

Simulated price movements are limited to approximately ±0.5% per update.

The thread pool configuration includes:

- Core pool size: 3
- Maximum pool size: 5
- Queue capacity: 20

The market update scheduler runs approximately every 10 seconds.

### 3. Notification Thread Pool

The `notificationExecutor` thread pool handles asynchronous notifications.

When an investment operation completes successfully, the application publishes a notification event.

A transactional event listener receives the event after the database transaction has committed.

The notification is then delivered asynchronously using Server-Sent Events (SSE). Relevant email listeners also use this executor to deliver welcome and transaction confirmation emails after successful commits.
The thread pool configuration includes:

- Core pool size: 2
- Maximum pool size: 4
- Queue capacity: 20

This prevents notification delivery from unnecessarily delaying the main investment operation.

### Thread Pool Summary

| Thread Pool | Core Threads | Maximum Threads | Queue Capacity | Responsibility |
|---|---:|---:|---:|---|
| `orderExecutor` | 3 | 5 | 10 | Execute pending limit orders |
| `marketExecutor` | 3 | 5 | 20 | Update simulated market prices |
| `notificationExecutor` | 2 | 4 | 20 | Deliver asynchronous SSE notifications and event-driven emails |

## Race Conditions and Thread Safety

One of the main challenges in Project 3 was ensuring that concurrent operations did not produce incorrect financial results.

The application uses several techniques to prevent race conditions.

### 1. Pessimistic Database Locking

The application uses JPA `PESSIMISTIC_WRITE` locking during operations that modify shared database records.

This protects critical data such as:

- Portfolio cash balances
- Holdings
- Investment orders
- Assets

For example, if two BUY operations attempt to use the same portfolio balance simultaneously, database locking ensures that one operation checks the updated balance after the other operation completes.

This prevents both operations from spending the same available funds.

### 2. Transaction Management

The application uses Spring's `@Transactional` annotation to maintain database consistency.

A financial operation may update several database records, including:

- Portfolio
- Holding
- Transaction
- Investment Order
- Audit Log

Transaction management ensures that related changes are committed together.

If an operation fails, its database changes are rolled back.

### 3. Thread-Safe Collections

The application uses `ConcurrentHashMap.newKeySet()` to track tasks currently queued or being processed.

Two separate sets track:

- Investment order IDs
- Asset IDs

This prevents the scheduler from submitting the same order or asset multiple times while an earlier task is still pending.

Task IDs are removed after processing completes or fails.

### 4. Status Validation

Before executing an order, the worker checks that its status is still `PENDING`.

Before updating an asset price, the worker checks that the asset is still `ACTIVE`.

These checks are performed after acquiring the relevant database locks.

This prevents duplicate order execution and avoids updating assets that have already been deactivated.

### 5. Concurrent REST Request Demonstration

A live demonstration uses two browser tabs signed in as the same test investor. Both tabs submit a POST /portfolio/buy request for two shares of the same asset while competing for a limited virtual cash balance.

For the tested example, the investor started with $1,000 and both BUY requests were submitted close together. One request succeeded and the other was rejected with "Insufficient available funds". The final balance remained non-negative, demonstrating that the portfolio lock and balance validation prevent overspending. Either request can win the lock; success is not tied to a particular browser tab.

For the classroom demonstration, AssetService.buyAsset() contains a temporary Thread.sleep(3000) after the portfolio lock is acquired to make overlapping requests easier to observe. This artificial delay is for the presentation only and must be removed afterward; it is not normal production behavior.


### 6. Transactional Notifications

The application uses:

`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`

This ensures successful investment notifications are only sent after the corresponding database transaction commits.

The application also uses:

`@Async("notificationExecutor")`

This allows notifications to be processed asynchronously.

If a financial transaction is rolled back, its after-commit notification is not sent.

### 7. Latest Execution Price Protection

Direct BUY/SELL operations lock the asset before reading the execution price. Limit-order execution performs an initial eligibility check, then acquires portfolio and asset locks and **rechecks the latest locked price** before filling. A BUY limit must still be at or below its limit and a SELL limit must still be at or above its limit. The checked price is used for calculations, holdings, and transaction records. This reduces inconsistent fills when the simulated market updates concurrently.


## API Rate Limiting

The application uses Bucket4j to protect selected endpoints from excessive requests.

### Authentication Rate Limiting

Login and password-reset requests are limited to three attempts per one-minute refill interval.

These limits are tracked separately for each email address.

### Project 3 — Investment Rate Limiting

Project 3 introduces a centralized rate-limiting filter for investment operations.

Each authenticated user receives an independent rate-limit bucket for each protected endpoint.

The configured limit is:

**5 requests per minute per user per endpoint.**

| HTTP Method | Endpoint | Rate Limit |
|---|---|---|
| POST | `/portfolio/buy` | 5 requests/minute |
| POST | `/portfolio/sell` | 5 requests/minute |
| POST | `/orders` | 5 requests/minute |
| PATCH | `/orders/{id}/cancel` | 5 requests/minute |

If the request limit is exceeded, the application returns:

**HTTP 429 — Too Many Requests**

The cancellation endpoint uses a normalized key so requests for different order IDs share the same cancellation limit.

GET requests and SSE subscriptions are not affected by this additional investment rate limiter.

Rate-limit buckets are currently stored in application memory, meaning they are not shared between multiple server instances.

Bucket4j uses interval-based token refilling rather than an exact sliding-window request counter.

## Real-Time and Email Notifications

### Server-Sent Events (SSE)

The application provides real-time investment notifications using Server-Sent Events (SSE).

Notifications are generated for important investment actions, including:

- Successful direct BUY operations
- Successful direct SELL operations
- Executed BUY limit orders
- Executed SELL limit orders
- Cancelled limit orders

The notification system uses application events and asynchronous processing.

Notifications are delivered after successful transaction commits.

Notification delivery is best-effort. Messages may not be delivered when a user is disconnected or the notification thread pool is saturated.

Rejected notification tasks are logged, but notifications are not currently stored for automatic retry.

Financial transactions remain committed even if a notification cannot be delivered.

### HTML Email Notifications

The application uses Spring Mail to send HTML emails for account verification, password resets, and successful account activation. After a successful direct BUY or SELL transaction, or an automatically executed BUY or SELL limit order, it sends an HTML confirmation with the transaction type, asset symbol, quantity, and total amount.

Welcome and transaction-confirmation emails use Spring application events, @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT), and @Async("notificationExecutor"). These messages are attempted only after the related database transaction commits, and email-delivery errors are logged rather than reversing a completed investment. Email delivery is best-effort; an automatic retry mechanism is not implemented.

## Audit Logging

The application maintains audit logs for important system operations.

Each audit log records:

- User email
- Action performed
- Action details
- Creation timestamp

Project 3 extends the audit actions with:

- `PLACE_LIMIT_ORDER`
- `CANCEL_LIMIT_ORDER`
- `EXECUTE_LIMIT_ORDER`

Audit logs for these operations are stored within the corresponding database transactions.

If an operation fails and its transaction is rolled back, the related success audit log is also rolled back.

## Testing and Verification

The backend uses **JUnit and Mockito**, including PostgreSQL integration tests, to check authentication, authorization, investment operations, order execution, database locking, simulated market updates, validation, and notifications. The project also includes a manual two-browser-tab concurrent BUY demonstration using a limited cash balance.

After the latest execution-price changes and related test fixes, the full Maven test run was reported as **BUILD SUCCESS**. The frontend portfolio-history implementation was reported with **35 passing tests**, plus passing build and lint checks. These are results from the development process; rerun both suites after the latest snapshot/backend and dashboard-layout changes to establish a current final total.

```powershell
# Backend (project root)
.\mvnw.cmd clean test

# Frontend
cd frontend
npm run build
npm run lint
npm test
```

Use the frontend test command configured in `frontend/package.json` if it differs from `npm test`.

## Development Approach and Planning

Development began with database design, user stories, authentication, and direct trading, followed by holdings, watchlists, admin tools, API documentation, and automated tests. Project 3 added limit orders, scheduled concurrent market updates, asynchronous notifications, stronger concurrency controls, financial performance analytics, and portfolio snapshots. Git/GitHub branches and pull requests were used to organize the work.

- [User Stories and Project Planning (Jira)](https://investmentportfolio.atlassian.net/jira/software/projects/SCRUM/boards/1/backlog)

## Entity Relationship Diagram

The ERD documents the **12 entities**, attributes, and relationships, including `portfolio_snapshots`, the reserved-cash/share fields, and the `cost_basis` and `realized_gain_loss` transaction columns.

[View the ERD in dbdiagram.io](https://dbdiagram.io/d/Investment-Portfolio-6aba48670f25a52d0128e646)

<!-- Replace this placeholder with the GitHub-uploaded image URL of your finalized 12-entity ERD. -->
<!-- Example: ![Entity Relationship Diagram](YOUR_NEW_ERD_IMAGE_URL) -->



## API Documentation

The REST API is documented using Swagger/OpenAPI. Swagger UI provides an interactive interface for viewing and testing the available API endpoints.

After starting the application, Swagger UI can be accessed at:

http://localhost:8081/swagger-ui/index.html

The OpenAPI specification is available at:

http://localhost:8081/v3/api-docs

For protected endpoints, click **Authorize** in Swagger UI and enter the JWT token using the Bearer authentication scheme.

## Installation and Configuration

Follow the steps below to set up and run the Virtual Investment Portfolio System locally.

### 1. Clone the Repository

Clone the project from GitHub:

```bash
git clone https://github.com/Shaikha-Projects/Virtual-Investment-Portfolio.git
```

Navigate into the project directory:

```bash
cd Virtual-Investment-Portfolio
```

### 2. Prerequisites

Make sure the following are installed:

- Java 17
- PostgreSQL
- Git
- Maven, or use the Maven Wrapper included with the project

### 3. Configure PostgreSQL

Create a PostgreSQL database named:

```text
investment_portfolio
```

Make sure PostgreSQL is running and configure the application's development properties with the correct database URL, username, and password.

Example database URL:

```text
jdbc:postgresql://localhost:5432/investment_portfolio
```

### 4. Configure Environment Variables

The application uses environment variables to keep sensitive information outside the source code.

Configure the required environment variables before starting the application.

```text
MAIL_USERNAME=your_email_address
MAIL_PASSWORD=your_email_app_password

ADMIN_EMAIL=your_admin_email
ADMIN_PASSWORD=your_admin_password
```

Also configure any database credentials and JWT secret required by your local application configuration.

Do not commit real passwords, email credentials, database credentials, or JWT secrets to GitHub.

### 5. Activate the Development Profile

Run the application using the `dev` Spring profile.

In IntelliJ IDEA, the active profile can be configured in the application's Run Configuration.

Alternatively, the profile can be supplied when starting the application:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

On Windows PowerShell:

```powershell
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### 6. Seed the Database

The application includes development seed data for investment assets.

When the application starts using the development configuration, the seed process populates the database with the initial asset data required to test the investment features.

An administrator account is also initialized using the configured environment variables:

```text
ADMIN_EMAIL
ADMIN_PASSWORD
```

### 7. Start the Application

The application can be started from IntelliJ IDEA by running the main Spring Boot application class.

It can also be started from the terminal.

On macOS/Linux:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

On Windows PowerShell:

```powershell
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Once the application has started successfully, the embedded Tomcat server runs the API locally on port `8081`.

### 8. Access the API

The base URL of the locally running application is:

```text
http://localhost:8081
```

API endpoints can be tested using Postman or Swagger UI.

Protected endpoints require a valid JWT obtained after successful authentication.

### 9. Access Swagger / OpenAPI

After starting the application, open Swagger UI at:

```text
http://localhost:8081/swagger-ui/index.html
```

The OpenAPI JSON specification is available at:

```text
http://localhost:8081/v3/api-docs
```

For protected endpoints, use the **Authorize** button in Swagger UI and provide a valid JWT using the Bearer authentication scheme.

### 10. Start the Frontend

The frontend is built using React and Vite and provides the user interface for interacting with the Virtual Investment Portfolio System.

Navigate to the frontend directory:

```bash
cd frontend
```

Install the required dependencies:

```bash
npm install
```

Start the frontend development server:

```bash
npm run dev
```

The frontend can then be accessed at:

```text
http://localhost:5173
```

Make sure the Spring Boot backend is also running on port `8081` so the frontend can communicate with the REST API.

## Challenges and Limitations

### Major Challenges

- **Authentication and authorization:** ensuring role-protected endpoints and rejecting previously issued JWTs for deactivated accounts.
- **Financial consistency:** ensuring purchases, sales, reservations, holdings, and transaction records remain consistent under concurrent requests.
- **Background processing:** separating market updates, automatic order execution, and notifications into managed executors while controlling duplicate submissions.
- **Data and UI growth:** adding realized P/L and recorded portfolio history without replacing existing dashboard functionality.

### Known Limitations

- The market is simulated and does not use live exchange prices.
- Older SELL transactions may not have recorded realized P/L and are not included in the recorded total until handled separately.
- Portfolio snapshots exist only from the time historical recording began; restarting the application can create snapshots close together.
- SSE notifications are best effort and can be missed while disconnected; they are not stored for durable replay.
- Rate-limit buckets are stored in memory and are not shared across multiple application instances.
- The temporary three-second delay in `AssetService.buyAsset()` is **demo-only** to make concurrent requests observable; remove it after the presentation.
- This is a local educational simulation, not a production brokerage.

## Future Improvements

- Real market data integration instead of simulated prices.
- Additional financial instruments such as ETFs, bonds, and cryptocurrencies.
- Price-threshold alerts connected to the market simulator and SSE.
- Accurate historical realized P/L backfill for old SELL transactions.
- More advanced order types, order expiry, and improved order-status history.
- Persistent notification inbox and delivery retries.
- Database migrations, containerization, CI/CD, monitoring, and deployment.
- Shared/distributed rate limiting and larger-scale performance tests.
- Longer-term portfolio analytics and more detailed administrative reporting.

## Credits and Resources

Developed as part of the **General Assembly Java Developer Bootcamp**. Official documentation and reference materials used during development:

- [Spring Boot](https://docs.spring.io/spring-boot/)
- [Spring Security](https://docs.spring.io/spring-security/reference/)
- [Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/)
- [React](https://react.dev/)
- [Vite](https://vite.dev/)
- [PostgreSQL](https://www.postgresql.org/docs/)
- [Swagger / OpenAPI](https://swagger.io/docs/)
- [JWT](https://jwt.io/)
- [JUnit 5](https://junit.org/junit5/docs/current/user-guide/)
