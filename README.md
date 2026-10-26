# Virtual Investment Portfolio System

## Project Description
The Virtual Investment Portfolio System is a secure REST API built using Java and Spring Boot. It allows users to simulate investment activities using virtual money without involving real financial transactions.
After registration and email verification, users receive a virtual starting balance of $100,000. Users can browse available assets, buy and sell assets, manage their holdings and watchlist, view their transaction history, and monitor their portfolio performance.
The system also provides administrative functionality for managing assets and user accounts. Project 3 extends the platform with BUY/SELL limit orders, concurrent market price simulation, asynchronous notifications, database concurrency protection, investment API rate limiting, and expanded audit logging. Security uses JWT authentication and role-based authorization for USER and ADMIN roles.

### Application Purpose

The purpose of this application is to provide a safe environment where users can simulate basic investment activities without using real money. Users can manage a virtual portfolio, make investment decisions, and track how those decisions affect their portfolio.

### Main Features

- User registration and email verification
- Secure login using JWT authentication
- Change password and password reset
- User profile management and profile picture upload
- Role-based authorization for USER and ADMIN
- Virtual portfolio with a $100,000 starting balance
- Browse, search, and filter investment assets
- Buy and sell assets using virtual funds
- View portfolio holdings and performance
- View paginated and sorted transaction history
- Add and remove assets from a watchlist
- Admin asset management
- Admin user deactivation using soft delete
- Real-time investment notifications using Server-Sent Events (SSE)
- Rate limiting for login and password-reset requests
- Per-user, per-endpoint rate limiting for investment operations (5 requests per minute)
- Place, view, and cancel BUY/SELL limit orders
- Automatic execution of pending limit orders when market prices meet their conditions
- Simulated market price updates and recorded price history
- Concurrent order execution and market updates using separate managed thread pools
- Asynchronous SSE notifications after successful transaction commits
- Transactional audit logs for order placement, cancellation, and execution
- Database concurrency protection using pessimistic locking
- Audit logging for important system actions
- Swagger/OpenAPI API documentation

## Technologies Used

- Java 17
- Spring Boot
- Spring Data JPA
- Spring Security
- JWT Authentication
- PostgreSQL
- Maven
- Hibernate
- Jakarta Bean Validation
- Spring Mail
- Server-Sent Events (SSE)
- Spring TaskExecutor / @Async / @Scheduled
- Bucket4j
- Swagger / OpenAPI
- JUnit
- Mockito
- Git and GitHub
- Postman
- React
- Vite
- JavaScript
- HTML / CSS

## Architecture

The application follows a layered architecture to separate responsibilities and keep the code organized and maintainable.

The main layers are:

- **Controller Layer** – Handles HTTP requests and responses and exposes the REST API endpoints.
- **Service Layer** – Contains the business logic, including investment operations, authentication, portfolio management, and administrative operations.
- **Repository Layer** – Communicates with the PostgreSQL database using Spring Data JPA.
- **Model Layer** – Contains the JPA entities that represent the application's persisted data.
- **DTO Layer** – Transfers request and response data between the API and the application without directly exposing entities.
- **Security Layer** – Handles JWT authentication, request filtering, and role-based authorization.
- **Exception Handling** – Provides centralized handling of application errors and returns appropriate HTTP responses.

### Main Domain Components

The main persisted components of the system include:

- User
- Portfolio
- Asset
- Holding
- Transaction
- Watchlist
- Email Verification Token
- Password Reset Token
- Audit Log
- Investment Order
- Price History

The database model contains 11 entities.

The main investment flow is:

User → Portfolio → Asset → Buy/Sell → Transaction → Holdings → Portfolio Performance

The limit-order flow is:

User → Place BUY/SELL Limit Order → PENDING → Market Price Check → FILLED (or user cancels → CANCELLED)

## General Approach

I approached the project by first identifying the main requirements and breaking them into smaller user stories and development tasks. I designed the database structure and relationships around the main investment workflow, where a user has a portfolio and can interact with available assets through buy and sell operations. I used a layered architecture to separate the controllers, business logic, database access, security, and data transfer objects.

I implemented the project incrementally, starting with user registration, email verification, authentication, and JWT security before moving to the investment features. The investment logic was implemented with business rules to prevent actions such as purchasing with insufficient funds or selling more shares than the user owns. After the core functionality was working, I added watchlists, portfolio performance, administrative asset management, user deactivation, audit logging, rate limiting, real-time SSE notifications, pagination and sorting, Swagger documentation, seed data, and automated testing. For Project 3, I extended investment functionality with limit orders, automatic concurrent order execution, concurrent simulated market price updates, reliable after-commit notifications, further audit logging, and database concurrency controls.

Git and GitHub were used throughout development with separate branches for features and pull requests to merge completed work into the main branch.

## Project 3 — Concurrency and Multithreading

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

The notification is then delivered asynchronously using Server-Sent Events (SSE).

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
| `notificationExecutor` | 2 | 4 | 20 | Deliver asynchronous SSE notifications |

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

### 5. Transactional Notifications

The application uses:

`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`

This ensures successful investment notifications are only sent after the corresponding database transaction commits.

The application also uses:

`@Async("notificationExecutor")`

This allows notifications to be processed asynchronously.

If a financial transaction is rolled back, its after-commit notification is not sent.

## Investment API Rate Limiting

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

## Real-Time Notifications

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

The application uses JUnit and Mockito for automated testing, along with PostgreSQL integration tests.

The last confirmed full regression run before the additional rate-limiting tests completed successfully with:

- **132 passing tests**
- **102 isolated PostgreSQL integration tests**

The tests cover important application functionality, including:

- Authentication and authorization
- Portfolio operations
- BUY and SELL transactions
- Concurrent financial operations
- Database locking
- Automatic limit-order execution
- Market price updates
- Asynchronous notifications
- Audit logging
- Business rule validation

## User Stories

The user stories for this project were created and managed using Jira. They were used to define the required functionality and track the implementation of the application's features.

[View User Stories in Jira](https://investmentportfolio.atlassian.net/jira/software/projects/SCRUM/boards/1/backlog)

## Entity Relationship Diagram (ERD)

The ERD shows the database entities, their attributes, and the relationships between the main components of the investment portfolio system.

[View ERD on dbdiagram.io](https://dbdiagram.io/d/Investment-Portfolio-6aba48670f25a52d0128e646)

<img width="1809" height="1251" alt="image" src="https://github.com/user-attachments/assets/1269894a-385f-4d82-bef8-1a68eef31c64" />

## Planning

Project planning and progress were managed using Jira. The Jira board was used to organize user stories, development tasks, deliverables, scope, and project progress throughout development.

[View Project Planning in Jira](https://investmentportfolio.atlassian.net/jira/software/projects/SCRUM/boards/1/backlog)


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

## Unsolved Problems

No critical issues have been reported in the covered financial, market-update, notification, and audit integration tests.
However, the full regression suite must be rerun after the recent rate-limiting changes.

### Known Limitations

- SSE notifications may be lost if a user is disconnected or the notification thread pool is saturated.
- Rate-limit buckets are stored in application memory and are not shared between multiple server instances.
  
## Major Challenges

One of the main challenges was designing secure authentication and authorization using Spring Security and JWT. The application needed to support different user roles while ensuring that protected functionality could only be accessed by authorized users. An additional consideration was user deactivation: an inactive user should not only be prevented from logging in again, but should also lose access when using a JWT that was issued before the account was deactivated. This was handled by validating the current user status during authenticated requests.

Another major challenge was maintaining data consistency during investment operations. A buy or sell operation affects multiple parts of the system, including the user's cash balance, holdings, and transaction history. I implemented the investment logic in the service layer with business rules that validate available funds, owned quantities, requested quantities, and asset status before completing a transaction.

Designing the application so that its features remained separated and maintainable was also an important part of the project. I used a layered architecture with controllers, services, repositories, DTOs, models, and security components, allowing each part of the application to have a clear responsibility as the project grew.

## Future Improvements

If more development time were available, the application could be extended with additional features such as:

- Integration with a real-time market data API to provide live asset prices.
- Additional asset types such as ETFs, cryptocurrencies, and bonds.
- More detailed portfolio analytics, charts, and historical performance tracking.
- More advanced transaction status workflows for pending or scheduled investment orders.
- Additional automated tests and integration tests to increase test coverage.
- Deployment to a cloud platform with a production PostgreSQL database.
- Enhanced real-time notifications for portfolio and asset price changes.
- Additional administrative reporting and monitoring features.

## Credits & External Resources

### Acknowledgments

Special thanks to **Zainab, Instructor Associate**, for her guidance, support, and feedback throughout the development of this project.


The following official documentation and resources were used as references during the development of this project:

- Spring Boot Documentation: https://docs.spring.io/spring-boot/
- Spring Security Documentation: https://docs.spring.io/spring-security/reference/
- Spring Data JPA Documentation: https://docs.spring.io/spring-data/jpa/reference/
- React Documentation: https://react.dev/
- Vite Documentation: https://vite.dev/
- PostgreSQL Documentation: https://www.postgresql.org/docs/
- Swagger / OpenAPI Documentation: https://swagger.io/docs/
- JWT: https://jwt.io/
- JUnit 5 Documentation: https://junit.org/junit5/docs/current/user-guide/

The project was developed as part of the Java Developer Bootcamp. External documentation was used for learning, implementation guidance, and reference.
