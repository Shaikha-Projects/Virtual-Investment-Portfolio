# Project 2 frontend verification

## Run locally (PowerShell)

Prerequisites: Java 17, PostgreSQL, and a Node version supported by the repository's Vite 8 installation. Use the committed Maven wrapper and npm lockfile.

1. Start PostgreSQL and create the existing `investment_portfolio` database if it does not exist.
2. In the repository root, supply the existing backend environment variables through your shell or IDE: `DB_PASSWORD`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `JWT_SECRET`, `ADMIN_EMAIL`, and `ADMIN_PASSWORD`. Use your own values; do not commit them. The JWT secret must meet the existing HS256 signing requirements. The backend creates the configured admin if it is absent.
3. Start the backend:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

4. In another terminal:

   ```powershell
   Set-Location frontend
   npm.cmd ci
   Copy-Item .env.example .env.local
   npm.cmd run dev -- --host localhost --port 5173 --strictPort
   ```

5. Open `http://localhost:5173`. The default backend is `http://localhost:8081`. Change `VITE_API_BASE_URL` in `.env.local` for a different backend, then restart Vite. Never place a JWT secret, password, or credential in a Vite variable: these variables are public build configuration.

The existing backend permits the frontend origin `http://localhost:5173`. A different frontend origin requires an explicit backend CORS configuration change. Verification and reset emails retain their existing localhost destinations; configure those separately before deployment. No deployment configuration was changed here.

## Automated checks

From `frontend`:

```powershell
npm.cmd run build
npm.cmd run lint
npm.cmd test
```

From the repository root:

```powershell
.\mvnw.cmd test
```

Frontend tests cover error redaction, business messages, encoded queries, JWT headers, authentication versus permission failures, network errors, cancellation after logout, and fragmented SSE parsing. Backend MVC tests use the real security configuration with mocked services, and exercise admin permissions, safe DTO serialization, identity, and SSE delivery. Service tests cover self-deactivation and bounded pagination. These do not replace browser or real-database integration testing.

## Execution record

- Frontend production build and ESLint passed.
- All 8 Node frontend tests passed.
- All 13 tests in the current Maven backend suite passed, including the real security configuration, self-deactivation prevention, and SSE delivery/replacement.
- A headless Chrome smoke test was executed against the real React application with a mocked fetch backend. It covered restored investor identity, hidden admin navigation for investors, a fractional buy and SSE display, selling the final holding, transaction pagination and sorting reset, profile display, logout, admin login, self-deactivation controls, and deactivation confirmation cancellation. No uncaught browser runtime exceptions were observed.
- Layout overflow checks passed at 375, 768, 1024, and 1440 CSS pixels. Mobile and desktop screenshots were visually inspected; tables scroll within their containers. Local screenshots are under `target/frontend-mobile-smoke.png` and `target/frontend-desktop-smoke.png` (generated artifacts, not committed source).
- This smoke test used fixtures. Live PostgreSQL, SMTP/email delivery, real browser-to-Spring trading, and the complete checklist below were not executed.

## Manual browser checklist (remaining end-to-end verification)

1. **Registration and verification:** Register with valid names, an eight-digit phone number, and a password of at least eight characters. Check required fields and malformed email/phone/password validation. Follow the verification email link and log in. Duplicate registration should show a concise conflict message, never a server response object.
2. **Session:** Refresh after login; the dashboard should return after identity validation. Replace the stored token with invalid text and refresh; it should be removed and login should explain the failure. Repeat with an expired token. Deactivate an account from another admin session and perform a protected action; it should return to login.
3. **Temporary outage:** Stop the backend and refresh with a valid token. A retry screen should appear without deleting the token. Restart the backend and retry. Stop it while navigating or submitting a form; show a short network error, preserve the session, and allow retry. For a timed-out trade, inspect transaction history before retrying because the server may have completed it.
4. **Logout isolation:** Open profile forms, trade panels, transactions, watchlist, and notifications. Log out, then sign in as a different investor. No prior user's values, notices, selected assets, files, or notifications should remain. Repeat with network throttling and logout while a request is pending. Token changes in another tab should end the current session.
5. **Passwords:** Request a reset email and follow the link, including while a JWT is stored. Reset to a valid password and return to login. Test an expired link. Log in and change the password from Profile; verify the old password fails on subsequent login.
6. **Dashboard:** Confirm cash, holdings, portfolio value, cost basis, and unrealized gain/loss agree with backend data. Numbers must be formatted as USD.
7. **Assets and trading:** Search by symbol/name and filter STOCK/ETF/CRYPTO. Search for no matches. Buy `0.01`, reject empty/zero/negative/nonfinite values, and attempt a purchase exceeding cash. Double-click Confirm; only one request should be sent while pending. Confirm success and the updated cash balance; visit holdings, transactions, dashboard, watchlist, and assets to verify fresh data.
8. **Holdings:** Sell a fractional position, reject selling more than owned, and sell the last holding. The holdings page must remain visible with its empty state and success notice. Verify balances/history after each trade.
9. **Transactions:** Create more than ten trades. Verify count, current page, total pages, Previous/Next boundaries, symbol/type filters, every sortable heading, ascending/descending/default cycling, and page reset after changing applied filters or sort. Check loading, no results, server failure, and Retry.
10. **Watchlist:** Add, attempt a duplicate, remove, and remove the final entry. Verify inline success/error feedback and empty state.
11. **Profile:** Edit names/phone, check phone validation, upload/replace an image, and reject non-images or files over 5 MB. Verify profile display, cancellation, and success messages.
12. **Admin:** Log in with the configured admin. The initial admin dashboard should show real asset counts and user count. List active/inactive assets, create/edit each supported type, check duplicate symbols/invalid prices, and activate/deactivate. Verify the investor catalog reflects active status.
13. **Admin users:** Page through users and confirm only safe account fields appear. Activate/deactivate another user; cancel and confirm deactivation. Your own Deactivate control must be disabled. A direct API attempt to self-deactivate must still fail. Try every admin endpoint with a normal user's bearer token: all must be forbidden. Normal users must see no admin navigation.
14. **SSE:** Open Notifications and wait for Live before trading. Confirm a buy/sell generates one matching named event and one list entry, with an Authorization header and no token in the URL. Disconnect/restart the backend and verify backoff/reconnection without duplicate entries. Log out and verify the stream is cancelled and no reconnect continues. Events are live only: missed events and earlier sessions are not replayed. The existing backend supports one stream per account; the latest connected tab receives events. Open a second tab and verify the previous tab reports that notifications are active elsewhere without repeatedly reconnecting.
15. **Responsive/accessibility:** Check 1440px, 1024px, 768px, and 375px widths. Verify Menu expands navigation on small screens, forms fit, and tables scroll inside their containers. Tab through forms, sorting, pagination, confirmation controls, notifications, and logout; test Enter submission, associated labels, visible focus, Skip to content, and browser zoom to 200%. Check that OS light/dark preferences preserve the application's dark theme.
16. **Error privacy:** Use browser request overrides or a test backend to return HTTP 400/401/403/404/409/500 with stack traces, JSON objects, and HTML. Only approved business messages or generic safe text should appear. Navigating away or retrying should clear obsolete action messages. No credentials or JWTs should appear in console logs.

## Scope and limitations

No schema changes, new runtime dependencies, Project 3 concurrency work, notification history, commits, or pushes were introduced. Notifications use authenticated streaming fetch with abort cleanup, capped reconnect backoff, and event IDs for deduplication. The backend retains its one-stream-per-account model and informs a replaced tab so it stops reconnecting. Client submission locks prevent repeated in-flight clicks; server-side trade idempotency is outside this change.

## Complete changed-file manifest

- `frontend/.env.example`
- `frontend/README.md`
- `frontend/VERIFICATION.md`
- `frontend/index.html`
- `frontend/package.json`
- `frontend/src/App.css`
- `frontend/src/App.jsx`
- `frontend/src/api/client.js`
- `frontend/src/api/format.js`
- `frontend/src/api/sse.js`
- `frontend/src/components/Notifications.jsx`
- `frontend/src/components/PortfolioShell.jsx`
- `frontend/src/components/UI.jsx`
- `frontend/src/hooks/useAction.js`
- `frontend/src/hooks/useResource.js`
- `frontend/src/hooks/useSession.js`
- `frontend/src/index.css`
- `frontend/src/pages/AdminPages.jsx`
- `frontend/src/pages/AuthPage.jsx`
- `frontend/src/pages/InvestorPages.jsx`
- `frontend/src/pages/ProfilePage.jsx`
- `frontend/src/styles.css`
- `frontend/tests/api.test.mjs`
- `src/main/java/com/ga/investmentportfolio/Controller/AdminUserController.java`
- `src/main/java/com/ga/investmentportfolio/Controller/AuthController.java`
- `src/main/java/com/ga/investmentportfolio/DTO/Response/AdminUserResponse.java`
- `src/main/java/com/ga/investmentportfolio/DTO/Response/SessionResponse.java`
- `src/main/java/com/ga/investmentportfolio/Service/AdminUserService.java`
- `src/main/java/com/ga/investmentportfolio/Service/NotificationService.java`
- `src/test/java/com/ga/investmentportfolio/Controller/FrontendAccessTest.java`
- `src/test/java/com/ga/investmentportfolio/Controller/NotificationControllerTest.java`
- `src/test/java/com/ga/investmentportfolio/Service/AdminUserServiceTest.java`
