# Blue Hour frontend verification

The final frontend phase extends the accepted Orbit-inspired Stage 1 foundation across authentication, asset market/details, holdings, transactions, orders, wallet, watchlist, alerts, AI Help, profile, administration, and shared states.

## Files changed in this phase

- `src/blue-hour.css` (new): shared presentation and page-specific responsive composition.
- `src/App.jsx`: loads the presentation layer after the accepted Stage 1 styles.
- `src/pages/AuthPage.jsx`: editorial authentication presentation; form handlers unchanged.
- `src/pages/InvestorPages.jsx`: page-specific styling hooks only.
- `src/pages/AdminPages.jsx`: styling hooks and operational heading copy only.
- `index.html`: light browser theme color.
- `tests/full-ui-browser-smoke.mjs` (new): isolated all-page rendering and interaction checks.
- Existing market, price-alerts, audit, role, and portfolio-history browser scripts: updated layout selectors/expectations and transient CDP navigation handling.

Existing Stage 1 files and unrelated working-tree changes predate this phase. No backend, Docker, API utility, authentication hook, financial calculation, or notification implementation was changed in this phase.

## Executed checks

- `npm.cmd test`: 85 passed, 0 failed.
- `npm.cmd run lint`: passed.
- `npm.cmd run build`: passed.
- Browser scripts: dashboard, orders, market, price-alerts, audit, role, portfolio-history, full-ui: passed.
- Full UI suite: 29 views at 1440, 768, and 390 pixels; 87 screenshots; no document overflow or browser exceptions.
- Representative screenshots manually inspected: login desktop, market desktop/tablet, wallet mobile, AI conversation desktop, admin dashboard desktop, user management mobile.

All browser API responses are synthetic fixtures. Actual React rendering and HTTP frontend delivery are exercised, but these are not live backend integration tests. Fixtures prevent forwarding API requests to real services. Controlled timers verify polling without waiting on real markets. PDF checks verify requests and UI behavior, not PDF document content. No real Stripe payment was performed.

## Artifacts

Project-root `target/final-ui/`: 87 PNG screenshots, `verification.json`, and `live-check.json`. Filenames use `<view>-1440.png`, `<view>-768.png`, and `<view>-390.png`.

Stage 1 dashboard artifacts remain under `target/stage-one/`.

## Running browser checks

Start the existing Vite frontend on port 5191 and a disposable headless Chrome on debugging port 9237. Set `SMOKE_APP_URL=http://127.0.0.1:5191` and `SMOKE_DEBUG_URL=http://127.0.0.1:9237`, then run `node tests/full-ui-browser-smoke.mjs`. Use a fresh browser page between older smoke scripts to isolate fixture injection.

## Live verification boundary

Read-only unauthenticated backend checks confirm protected Assets and Notifications endpoints reject missing authentication and the Authentication endpoint responds. Assets CORS preflight was checked with the frontend origin. Exact statuses are recorded in `live-check.json`.

Before release, sign in with an existing test account and confirm live assets/dashboard updates, portfolio and wallet values, trading/order actions, notifications, AI Help, profile, administration, and Stripe sandbox return/recovery. No valid live user session was available to this run. Responsive tables intentionally scroll within their own containers on smaller screens.

The frontend build and mocked regression checks are ready for CI/CD work. Authenticated live acceptance remains a release check. No CI/CD or AWS work was started.

## Final viewport layout adjustment

Files: `src/blue-hour.css`, `src/components/PriceHistoryChart.jsx`, `src/components/PortfolioShell.jsx`, new `tests/workspace-browser-smoke.mjs`, and small test synchronization/diagnostic fixes in `tests/full-ui-browser-smoke.mjs` and `tests/market-browser-smoke.mjs`.

Desktop uses a `100dvh` flex workspace with a non-scrolling header and accessible `overflow-y: auto` main content. Long tables have their own bounded scroll region and sticky headings. Navigation resets the main scroll position. Tablet/mobile retain natural document scrolling. No overflow hiding was added.

Dashboard summary, spacing, table rows, allocation, and charts are compacted proportionally. Short desktop screens place financial metrics alongside the large portfolio value. CSS viewport sizing sets chart height; ResizeObserver updates SVG geometry without scaling down chart labels. Detached chart resize callbacks are ignored on navigation.

Measured with five synthetic holdings at 100% browser zoom:

| Viewport | Document scrolling | Dashboard content scrolling |
| --- | --- | --- |
| 1366 x 768 | None | None |
| 1440 x 900 | None | None |
| 1920 x 1080 | None | None |
| 1366 x 640 | None | Required and available |
| 768 x 1024 | Natural | Natural document flow |
| 390 x 844 | Natural | Natural document flow |

Screenshots and measured section bounds are in project-root `target/workspace-layout/`. All approved sections, including both charts, five holdings, allocation and AI Help, are within the visible desktop viewport at the three requested sizes. Browser checks also exercise long-table scrolling, sticky headings, navigation dropdowns, form focus, chart keyboard interaction, notifications and confirmations. Larger text, zoom, unusually long content, and error banners can require content scrolling; content remains accessible rather than being clipped. These checks use synthetic API responses, not live financial accounts.

Final rerun after the viewport changes: 85 unit tests passed; ESLint passed; Vite production build passed; all nine browser suites passed (workspace, dashboard, full-ui, market, orders, price-alerts, audit, role, portfolio-history). The full-ui suite covered 29 views at three widths. Desktop screenshots at all three requested sizes and tablet/mobile screenshots were visually inspected. No CI/CD or deployment work was started.
