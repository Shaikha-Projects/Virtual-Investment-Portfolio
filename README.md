# Virtual Investment Portfolio System

## Project Description

The Virtual Investment Portfolio System is a secure REST API built using Java and Spring Boot. It allows users to simulate investment activities using virtual money without involving real financial transactions.
After registration and email verification, users receive a virtual starting balance of $100,000. Users can browse available assets, buy and sell assets, manage their holdings and watchlist, view their transaction history, and monitor their portfolio performance.
The system also provides administrative functionality for managing assets and user accounts. Security is implemented using JWT authentication and role-based authorization for USER and ADMIN roles.

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
- Rate limiting for login attempts
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

The main investment flow is:

`User → Portfolio → Asset → Buy/Sell → Transaction → Holdings → Portfolio Performance`

## General Approach

I approached the project by first identifying the main requirements and breaking them into smaller user stories and development tasks. I designed the database structure and relationships around the main investment workflow, where a user has a portfolio and can interact with available assets through buy and sell operations. I used a layered architecture to separate the controllers, business logic, database access, security, and data transfer objects.

I implemented the project incrementally, starting with user registration, email verification, authentication, and JWT security before moving to the investment features. The investment logic was implemented with business rules to prevent actions such as purchasing with insufficient funds or selling more shares than the user owns. After the core functionality was working, I added features such as watchlists, portfolio performance, administrative asset management, user deactivation, audit logging, rate limiting, real-time SSE notifications, pagination and sorting, Swagger documentation, seed data, and automated testing.

Git and GitHub were used throughout development with separate branches for features and pull requests to merge completed work into the main branch.

## User Stories

The user stories for this project were created and managed using Jira. They were used to define the required functionality and track the implementation of the application's features.

[View User Stories in Jira](https://investmentportfolio.atlassian.net/jira/software/projects/SCRUM/boards/1/backlog)

## Entity Relationship Diagram (ERD)

The ERD shows the database entities, their attributes, and the relationships between the main components of the investment portfolio system.

[View ERD on dbdiagram.io](https://dbdiagram.io/d/Investment-Portfolio-6aba48670f25a52d0128e646)

<img width="1836" height="1235" alt="Investment Portfolio ERD" src="https://github.com/user-attachments/assets/76c213a6-0028-4df7-b22e-b66f3e09794c" />

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

There are currently no known critical issues affecting the core functionality of the application.

The application has been tested for its main workflows, including authentication, investment operations, role-based authorization, user deactivation, and important business rules. Further testing and refinement could still be performed for additional edge cases and production-level deployment.

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
