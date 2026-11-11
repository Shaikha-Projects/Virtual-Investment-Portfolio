# Virtual Investment Portfolio frontend

React 19 and Vite investor/admin interface for the Authentication, Investment, and Notification microservices.

See [VERIFICATION.md](VERIFICATION.md) for prerequisites, environment configuration, exact run commands, automated checks, and the full manual verification checklist.

```powershell
npm.cmd ci
Copy-Item .env.example .env.local
npm.cmd run dev -- --host localhost --port 5173 --strictPort
```

Start Authentication on `http://localhost:8084`, Investment on `http://localhost:8085`, and Notification on `http://localhost:8083`. Configure `VITE_AUTH_BASE_URL`, `VITE_INVESTMENT_BASE_URL`, and `VITE_NOTIFICATION_BASE_URL` in `.env.local` if needed; these are also the development defaults. Restart Vite after changing them. All three services allow the frontend origin `http://localhost:5173`.

The shared API client routes Authentication/account requests to Authentication and investing requests to Investment. SSE connects to Notification. Profile images are served by Authentication from its existing `uploads/` directory. The frontend does not use the original monolith. Keep backend credentials and internal service keys out of Vite variables.

```powershell
npm.cmd run build
npm.cmd run lint
npm.cmd test
```

The frontend contains no credentials. Vite environment variables are public configuration. Notification entries are live events held only in the current browser session.
