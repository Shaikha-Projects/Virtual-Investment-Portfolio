# Dark trading UI redesign

## Implemented

- Shared navy/emerald design system across investor, admin, authentication, profile, trading, tables, alerts, empty states, and live notifications.
- Collapsible desktop sidebar, icon navigation, account header, notification bell, mobile drawer with Escape/Tab handling and focus restoration, reduced-motion support, and horizontally scrollable tables.
- Investor dashboard with prominent total value, cash, holdings market value, invested cost basis, unrealized gain/loss, quick actions, asset allocation, capital composition, value comparison, holdings breakdown, and recent transactions.
- Client-side asset sorting preserves the existing server search/type filters. Holdings show unrealized position gains derived from their current value, quantity, and average acquisition price. Trading validation, request locks, API paths, and refresh behavior are retained.
- Admin overview, management screens, forms, badges, and confirmation panels use the same theme. Role gating and self-deactivation controls remain in place.
- Authentication screens use a decorative investment-themed identity with no fabricated financial data.

## Data sources and chart definitions

The existing backend exposes current performance through `GET /portfolio/performance`, current holdings through `GET /portfolio/holdings`, and paginated transactions through `GET /portfolio/transactions`. Inspection found no historical portfolio-performance endpoint or stored historical portfolio-value series. No historical line chart was added.

- Portfolio KPI values come directly from the current performance response.
- Donut segment percentage = position current value / sum of positive current holding values × 100. The legend includes the underlying amounts; zero-value positions do not produce invented segments. Values are sorted by market value without modifying the source response.
- Capital composition uses current cash and current holdings market value. Invested cost basis is compared directly with current holdings value.
- Displayed position unrealized P/L = current value − quantity × average buy price. The backend's financial calculations and database are unchanged.
- Recent activity requests five transactions ordered by `createdAt,desc`. No fake history, prices, daily changes, market status, or account totals are displayed.

## Files changed in this redesign

Modified:

- `frontend/index.html`
- `frontend/VERIFICATION.md`
- `frontend/src/styles.css`
- `frontend/src/components/PortfolioShell.jsx`
- `frontend/src/components/Notifications.jsx`
- `frontend/src/components/UI.jsx`
- `frontend/src/pages/InvestorPages.jsx`
- `frontend/src/pages/AdminPages.jsx`
- `frontend/src/pages/AuthPage.jsx`
- `frontend/src/pages/ProfilePage.jsx`

Created:

- `frontend/src/components/Icon.jsx`
- `frontend/src/api/analytics.js`
- `frontend/src/pages/DashboardPage.jsx`
- `frontend/tests/analytics.test.mjs`
- `frontend/DARK_UI_REDESIGN.md`

Changes from the earlier Project 2 implementation were already present in the workspace and are retained. No backend, database, authentication/session hook, API client, SSE parser/connection logic, or runtime dependency changes were needed for the redesign. Notification changes are limited to presentation.

## Validation

- `npm.cmd run build`: passed.
- `npm.cmd run lint`: passed.
- `npm.cmd test`: all 11 tests passed (8 existing API/SSE tests and 3 analytics tests).
- Headless Chrome was exercised against the actual React app with a mocked fetch backend. All investor/admin pages and login, registration, forgot-password, and reset-password views rendered without uncaught browser exceptions.
- Browser assertions verified a known portfolio fixture: $10,000 total value, $9,500 cash, $500 holdings market value, $450 invested cost, 60%/40% allocation, and 5% invested composition. These numbers exist only in the test fixture, never in application code.
- Browser checks covered restored sessions, investor/admin navigation, fractional buy/sell, SSE display, final-holding empty state, transaction pagination/sort reset, profile, logout, admin self-deactivation controls, and cancellation of deactivation confirmation.
- Dashboard layouts were checked at 1440, 1024, 768, and 375 CSS pixels after layout transitions settled. No document-level horizontal overflow was found. Mobile menu focus, Shift+Tab containment, Escape, and scroll restoration were checked.
- Desktop/mobile dashboard, auth, trading, and admin screenshots were reviewed. Generated screenshots and the local smoke harness are under `target/`, outside the source change set.

This was a mocked browser smoke test, not a live PostgreSQL/SMTP/Spring end-to-end test. Real account flows and live network behavior should still be checked using the existing [verification guide](VERIFICATION.md). No claim is made that live backend integration tests were executed for this visual redesign.

## Run and inspect

Use the existing backend environment setup and run the backend as documented in `VERIFICATION.md`. From `frontend`:

```powershell
npm.cmd ci
npm.cmd run dev -- --host localhost --port 5173 --strictPort
```

Open `http://localhost:5173`. If needed, copy `.env.example` to `.env.local` and set the public `VITE_API_BASE_URL`; restart Vite after changing it. No secrets belong in Vite configuration.

```powershell
npm.cmd run build
npm.cmd run lint
npm.cmd test
```

No Project 3 concurrency work, commits, or pushes were performed. Live-only notifications retain the backend's existing one-stream-per-account behavior.
