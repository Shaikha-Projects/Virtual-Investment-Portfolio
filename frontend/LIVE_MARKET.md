# Live market prices and asset history

The investor Assets page is the market page. Its existing search, filters, sorting,
watchlist and trading remain available. Click an asset name or symbol to open its
details, then use Back to Market to return with filters and sorting preserved.
The application uses section state rather than React Router; details follow that
existing navigation pattern and have no sidebar entry or separate URL.

## Data and lifecycle

- Uses the existing authenticated API client and configurable API base URL.
- Polls `GET /assets` with existing search/type query parameters every 10 seconds.
- Details reuse that catalog snapshot and separately poll
  `GET /assets/{assetId}/price-history` every 10 seconds.
- Movement compares consecutive successful catalog responses, not daily change.
- History displays the latest 100 valid actual records in chronological order.
  The backend still sends its complete history; no artificial samples are added.
- The interactive SVG chart supports pointer/touch inspection, keyboard arrows,
  Home/End, timestamps, responsive sizing and single-record series. No new dependency.
- Background failures keep the previous data with a warning. Initial requests
  have loading/error/empty states. Authentication failures use existing session handling.
- Poll requests do not overlap. Timers and requests are cancelled when leaving
  their view or logging out. Trade form state survives background price updates.
- Instant trading reuses the extracted existing TradeForm without changing its
  validation or API behavior. Holdings determine whether Sell is available.
- Dashboard, backend, authentication and order business logic are unchanged.

## Validation

From `frontend/` run `npm run build`, `npm run lint`, and `npm test`.
All passed, including 30 unit tests. Chrome browser smoke checks also passed:
`tests/market-browser-smoke.mjs`, `tests/role-browser-smoke.mjs` and
`tests/orders-browser-smoke.mjs`. These use mocked API responses, not a live backend.
The market checks use controlled polling timers and cover movement, latest-100
history, keyboard/hover, filters, trading input preservation, errors/retry,
empty/single series, request overlap, cleanup and widths 375/768/1440px.

Optional browser harnesses require a disposable Chrome remote-debugging instance
and a running Vite server. Set `SMOKE_APP_URL` and `SMOKE_DEBUG_URL` to their URLs
and run each script with Node, sequentially against the same browser.

## Manual verification against the real backend

1. Start the existing backend normally. Configure `VITE_API_BASE_URL` as already
   documented by the application, then run `npm install` and `npm run dev` here.
2. Log in as USER. Check the Dashboard is unchanged and contains no price-history chart.
3. Open Assets. Search/filter/sort, then observe prices for at least 20 seconds.
   Confirm green up/red down/neutral indicators match successive API prices.
4. Enter a Buy quantity and wait for a poll; verify the quantity remains. Exercise
   instant Buy/Sell and limit orders using an appropriate test account.
5. Click an asset. Verify current price, type and name, and compare chart samples
   to the latest 100 response records. Hover/tap and use arrows/Home/End.
6. Return to Market and verify retained filters/sorting. Try phone/tablet widths.
7. Check new assets without history and single-record series. Stop the backend:
   old data should remain with a friendly warning. Restart it and retry.
8. Leave details/Assets and inspect network activity: their polls should stop.
   Log out or expire the token and verify normal session cleanup. Switch accounts
   and confirm no previous account information appears.
9. Verify My Orders/cancellation, holdings, transactions and watchlist. Log in as
   ADMIN and verify existing admin screens remain available with no investor menu.

No backend files or dependencies were changed. No commit or push was made.
