# Limit Order Management frontend

## Implemented

- USER-only **My Orders** navigation and page, using the existing role-guarded section navigation. This repository does not use React Router; no routing library or replacement navigation was introduced.
- Orders table with Asset, Order Type, Quantity, Limit Price, Status, Created Date, Executed Date, and Action. The array is rendered exactly in backend order, without client sorting. Missing execution dates display a dash.
- Symbol lookup through the existing active asset catalog, with an explicit `Asset #<id>` fallback when an asset is inactive, missing from the catalog, or lookup is unavailable. Lookup failure does not prevent viewing/cancelling orders.
- BUY and SELL limit-order form with asset/type selection, minimum validation, estimated order value, execution-rule explanations, submission locking, and safe feedback. New orders are fetched from the backend after submission; there is no invented local order record or client-side execution.
- Native confirmation dialog for PENDING-only cancellation. The backend decides ownership, final status, and reservation release; race-condition errors remain visible and understandable.
- ADMIN-only **Execute Pending Orders** control on the Admin Dashboard, with confirmation, pending state, safe feedback, and refresh of available admin data.
- Dark, responsive layout, order status colors, existing authentication, role separation, market trading, pagination, and notifications retained. Market buy/sell forms are unchanged.

## Verified API contracts and integration differences

| Method | Actual endpoint | Response/use |
| --- | --- | --- |
| GET | `/orders` | JSON array of the authenticated investor's orders |
| POST | `/orders` | Plain-text success; request fields `assetId`, `orderType`, `quantity`, `limitPrice` |
| PATCH | `/orders/{id}/cancel` | Plain-text success |
| POST | `/admin/assets/execute-pending` | Plain-text success; existing server-side `hasRole('ADMIN')` |
| GET | `/assets` | Existing active asset catalog for form selection and symbol lookup |

**Endpoint discrepancy:** The requested `/orders/execute-pending` is not mapped in the checked-in controllers. The implemented action is in `AdminAssetController`, whose class mapping is `/admin/assets`, making the actual URL `/admin/assets/execute-pending`. The frontend uses that verified existing URL; it does not create, assume, or probe another endpoint.

`CreateOrderRequest` requires both quantity and limit price to be at least **0.01**, so the form matches those actual constraints rather than accepting smaller positive values that the backend rejects. No available-cash/share calculation is duplicated in the frontend. Existing services remain responsible for reservations and execution.

The API client adds an explicit text-response mode for these three mutation endpoints. Existing JSON callers keep their original behavior. Known order business-rule errors are allowlisted; arbitrary Java exceptions, response bodies, and stack traces are never displayed. Mutation responses use fixed success messages rather than directly rendering server text.

## Existing backend limitations

- GET `/orders` is scoped to the authenticated investor's portfolio. There is no verified admin-wide order-list API, so the admin interface does not invent one or call the investor endpoint for an admin account.
- Execution returns no counts or affected account/order IDs. The UI therefore reports successful processing without claiming that every order filled. Available admin data refreshes after execution. Investors refresh **My Orders** to retrieve their own updated statuses.
- The existing SSE events do not provide verified limit-order status updates, so no automatic order-status subscription is assumed. Refresh is explicit.
- Inactive assets may not be in `/assets`. Their orders remain accessible using asset IDs.
- No live Spring/PostgreSQL end-to-end execution was performed by the coding agent. The controllers/services were inspected read-only; browser integration checks used isolated API fixtures. Production application code always uses real configured backend APIs.

No backend files, configuration, dependencies, security, database, schema, or business logic were changed.

## Files created

- `frontend/src/api/orders.js`
- `frontend/src/pages/OrdersPage.jsx`
- `frontend/src/components/ConfirmDialog.jsx`
- `frontend/src/components/ExecutePendingOrders.jsx`
- `frontend/tests/orders.test.mjs`
- `frontend/tests/orders-browser-smoke.mjs`
- `frontend/ORDER_MANAGEMENT.md`

## Files modified

- `frontend/src/api/client.js`
- `frontend/src/auth/roles.js`
- `frontend/src/components/PortfolioShell.jsx`
- `frontend/src/pages/AdminPages.jsx`
- `frontend/src/styles.css`
- `frontend/tests/roles.test.mjs`
- `frontend/tests/role-browser-smoke.mjs`

## Validation performed

- Frontend production build and ESLint passed.
- All **25 Node frontend tests** passed: 16 existing/regression tests plus 9 order validation/API tests.
- Existing role browser smoke checks passed with the additional My Orders destination, including ordinary market trading, role separation, refresh, logout, profile, pagination, and SSE.
- New headless Chrome orders smoke checks passed against the actual UI with a mocked fetch backend. Coverage: server ordering, all status badges, symbol/ID fallback, BUY/SELL placement, estimate, native minimum validation, duplicate-submission prevention, plain-text success responses, cancellation confirmation, no cancellation for filled/cancelled orders, cancellation races, business/server/network error handling, retries, empty state, responsive 375px layout, ADMIN-only execution, execution confirmation/errors, relevant admin-data refresh, subsequent investor-status refresh, and JWT headers. No uncaught browser runtime exceptions were observed.
- Backend source, resources, and tests were compared byte-for-byte with a task-start snapshot after implementation. No backend tests were run or changed for this frontend-only task.

## Run and manually verify

Run the backend using its existing setup without modifying it. From `frontend/`:

```powershell
npm.cmd run dev -- --host localhost --port 5173 --strictPort
```

Use the existing `VITE_API_BASE_URL` configuration if needed. The existing backend CORS setup permits `http://localhost:5173`.

1. Log in as USER, open My Orders, and confirm server order, asset names/fallback IDs, statuses, dates, loading/error/empty states, and Refresh.
2. Open Place Limit Order. Choose an active asset and BUY, enter quantity and price, and check the estimate and BUY rule. Submit once, verify success, and confirm the newly fetched first row. Repeat with SELL and verify its rule.
3. Check blank, zero, negative, and sub-0.01 values. Submit an order exceeding available cash/shares and verify the safe backend business message. Double-click submission and confirm only one request occurs while pending.
4. For a pending order, open Cancel and choose Keep order; verify no PATCH was sent. Confirm cancellation, then verify CANCELLED. FILLED/CANCELLED rows must have no cancel action. If an order fills before cancellation, verify the backend error is shown and refresh its status.
5. Confirm USER cannot see the execution control or navigate to admin pages.
6. Log out and log in as ADMIN. Confirm My Orders is absent. Open Execute Pending Orders, dismiss the dialog, then confirm. Verify the actual request is POST `/admin/assets/execute-pending` and the success/error feedback is appropriate.
7. Switch back to the investor and refresh My Orders. Eligible orders should be FILLED with execution dates; ineligible orders should remain PENDING. Compare cash/holdings/transactions against backend results.
8. Stop the backend, retry loading/submitting, and verify safe errors without losing a valid session. If a mutation times out, refresh orders before retrying, as the server may already have accepted it; the frontend never automatically retries mutations.
9. Check mobile navigation, horizontally scrollable orders table, stacked fields, and confirmation dialogs using keyboard Tab/Escape and visible focus states. Confirm existing market buy/sell and unrelated pages are unchanged.

Automated checks:

```powershell
npm.cmd run build
npm.cmd run lint
npm.cmd test
```

Optional browser checks require a disposable headless Chrome instance and separate Vite server. From `frontend/`, start Vite in one terminal:

```powershell
npm.cmd run dev -- --host localhost --port 5177 --strictPort
```

In another terminal:

```powershell
$ordersProfile = Join-Path (Get-Location) 'node_modules\.cache\orders-ui-chrome'
Start-Process -FilePath 'C:\Program Files\Google\Chrome\Application\chrome.exe' -WindowStyle Hidden -ArgumentList @('--headless=new', '--disable-gpu', '--disable-extensions', '--no-first-run', '--remote-debugging-port=9229', "--user-data-dir=$ordersProfile", 'about:blank')
$env:SMOKE_APP_URL = 'http://localhost:5177'
$env:SMOKE_DEBUG_URL = 'http://localhost:9229'
node tests/role-browser-smoke.mjs
node tests/orders-browser-smoke.mjs
```

These scripts clear the dedicated test origin's browser storage and replace fetch responses only in that disposable browser. Stop the dedicated browser/server afterward. No backend dependencies or runtime frontend dependencies were added. No commits or pushes were made.
