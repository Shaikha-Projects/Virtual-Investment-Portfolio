# Investment Portfolio Investor User Guide

## About this application

Investment Portfolio is an educational application for practising investing with virtual USD funds. Asset prices are simulated, not live exchange prices. Stripe payments use a sandbox, and withdrawals do not transfer money to a bank account.

This guide explains the investor features currently available in the application. It does not provide personal investment recommendations.

## Registration, email verification, and login

### How do I create an account?

Choose **Create an account** on the login page. Enter your first name, last name, email address, an eight-digit phone number, and a password of at least eight characters.

After registration, check your email for a verification link. Check your spam or junk folder if you cannot find it.

### Why must I verify my email?

You must verify your email before logging in. Verification activates your account. An inactive account cannot log in, even if its email was previously verified.

Verification links expire after one hour. An expired or invalid link cannot verify an account. The current login page does not provide a resend-verification button; ask the application administrator for help if needed.

### What is my starting balance?

The application creates a portfolio after email verification with **$0 in virtual cash** and zero initial funding. Add virtual funds through **Cash Wallet** using Stripe Sandbox before buying assets or placing BUY limit orders. Portfolio creation may take a short time. Existing portfolios keep their balances, investments, history, and recorded initial funding. Verifying an account again does not reset an existing portfolio or add funds.

### How do I log in or log out?

Use your registered email address and password to log in. Use the logout control when you finish, especially on a shared device. If your session expires, log in again.

### What if I forget my password?

Choose **Forgot password?**, enter your email address, and follow the reset link sent to an eligible account. Reset links expire after one hour. Choose a new password of at least eight characters, then return to login.

## Profile and account management

Open **Profile** to view your name, email address, phone number, and profile picture.

- **Edit Profile** changes your first name, last name, and optional phone number. A supplied phone number must contain exactly eight digits.
- **Change Password** requires your current password and a new password of at least eight characters.
- **Profile Picture** lets you upload or replace an image. The page accepts images up to 5 MB.

The profile form does not change your email address. Investor pages do not provide account deletion or self-deactivation. Ask the administrator about account access problems; do not share your password.

## Available investment assets

Open **Assets** to browse active assets. Search by symbol or name, or filter by **STOCK**, **ETF**, or **CRYPTO**.

Asset details include simulated prices and recorded price history. Prices can change while you use the application. Inactive assets cannot be traded or added to a watchlist.

### Are these real market prices?

No. The application generates simulated price changes. Charts show recorded simulation data and may have little or no history for a new asset.

## Buying and selling assets

### How do I buy an asset now?

Choose an active asset from **Assets**, select the buy action, enter a quantity, and submit. A successful purchase deducts virtual cash and adds the asset to your holdings.

The application checks available cash before completing a purchase. The final price is the simulated price used when the server processes the trade; a displayed price is not a guaranteed quote.

### How do I sell an asset?

Use the sell action for an asset you own and enter the quantity. A successful sale reduces your holding and adds virtual cash to your portfolio.

You cannot sell more than your available quantity. Shares reserved for pending SELL limit orders are not available for another sale. Selling an entire unreserved holding removes that position from your holdings.

### Are market orders supported?

The direct buy and sell actions execute against the current simulated price. They are the application's immediate trading actions. **My Orders** is for pending limit orders, rather than a separate queued market-order system.

## Limit orders

Open **My Orders** and use **Place a limit order**. Choose an asset, BUY LIMIT or SELL LIMIT, quantity, and limit price. Quantity and limit price must each be at least 0.01.

- A **BUY LIMIT** order can execute when the simulated price is at or below your limit price.
- A **SELL LIMIT** order can execute when the simulated price is at or above your limit price.

An accepted order starts as **PENDING**. The application checks pending orders in the background, so execution is not necessarily immediate when a price changes. A successful execution becomes **FILLED** and appears in your trading transaction history.

There is no guarantee that a limit order will execute. It must meet its price condition and pass the application's checks when processed.

## Reserved cash and reserved shares

### Why is my available cash lower than my cash balance?

A pending BUY limit order reserves cash equal to its quantity multiplied by its limit price, rounded to cents.

**Available cash = cash balance - reserved cash.**

Reserved cash is still part of your cash balance, but cannot fund another purchase or a withdrawal. When the order fills, its reservation is released and the actual purchase cost is deducted. Cancelling a pending order releases its reservation without making a purchase.

### Why can I not sell all my shares?

A pending SELL limit order reserves its requested quantity.

**Available shares = owned shares - reserved shares.**

Reserved shares cannot be sold again while the order is pending. Filling the order sells the shares; cancelling it makes them available again.

## Cancelling an order

In **My Orders**, choose **Cancel** for your PENDING order and confirm. Cancellation releases the cash or shares reserved for that order.

Only pending orders can be cancelled. If an order fills while you are trying to cancel it, cancellation may be rejected. Refresh the list and check the final status before taking another action.

The current order form does not edit an existing order. To change a pending order, cancel it successfully and then place a new order.

## Portfolio balance, holdings, and performance

The **Dashboard** summarises your portfolio. **My Holdings** shows the assets you own, including quantities, purchase information, current values, and gain or loss.

- **Cash balance** is your total virtual cash, including cash reserved for pending BUY orders.
- **Holdings value** is the current simulated value of your owned assets.
- **Total portfolio value** is cash balance plus holdings value.
- **Cost basis** is the purchase cost assigned to your remaining holdings.
- **Unrealized gain or loss** compares the current holdings value with their cost basis.
- **Realized gain or loss** records gains or losses from completed sales.

Overall performance accounts for wallet funding:

- Contributed capital is initial funding plus completed wallet deposits.
- Overall gain or loss is total portfolio value minus contributed capital, plus completed wallet withdrawals.
- Overall return percentage is overall gain or loss divided by contributed capital, multiplied by 100. The application returns zero when contributed capital is zero.

A completed deposit is funding, not investment profit. A completed withdrawal is not, by itself, an investment loss. Pending, failed, and cancelled wallet operations do not count as completed funding.

### What does the portfolio history chart show?

The chart shows recorded portfolio values. Choose **1D**, **1W**, **1M**, or **ALL** to change the displayed period. These values are snapshots, not a continuous record of every price change. Deposits and withdrawals can change the portfolio value shown on this chart; it is not a funding-adjusted investment-return chart.

### Where do I find past trades?

Open **Transactions** for BUY and SELL history. You can filter by asset symbol and transaction type and use the table's sorting and pagination. Wallet deposits and withdrawals have a separate history in **Cash Wallet**.

## Watchlists

Add an active asset to your watchlist from **Assets**. Open **Watchlist** to see its symbol, name, current price, and added date. Choose **Remove** to remove an entry.

Adding an asset to a watchlist does not buy it, reserve funds, or create a price alert. The same asset cannot be added to your watchlist twice.

## Price alerts

Open **Price Alerts**, choose an asset, select a condition, and enter a target price.

- **At or above** requires a target higher than the current price when you create the alert. It triggers when an updated price reaches or exceeds the target.
- **At or below** requires a target lower than the current price when you create the alert. It triggers when an updated price reaches or falls below the target.

Alerts start as **ACTIVE** and become **TRIGGERED** when their condition is reached. A triggered alert sends an in-app notification and an email. It does not place a trade.

You can cancel an ACTIVE alert. Triggered and cancelled alerts cannot be cancelled again. Create a new alert if you want another notification after an alert has triggered.

## Cash Wallet deposits through Stripe Sandbox

Open **Cash Wallet** to view cash balance, reserved cash, available cash, and wallet history.

### How do I deposit virtual USD?

1. Enter a positive USD amount with no more than two decimal places.
2. Choose **Continue to Stripe**.
3. Complete the hosted Stripe sandbox checkout using test payment details only.
4. Return to the application and allow it to check the payment.
5. Confirm the wallet operation is **COMPLETED**, then check your refreshed balance and history.

The application has a configurable per-deposit limit. If your amount exceeds it, the request is rejected.

Creating a deposit or opening Checkout does not add money. A success redirect alone is not payment confirmation. The application must verify the payment before crediting virtual cash.

### What if a deposit is still pending?

Find it in wallet history and choose **Check payment**. If it is unpaid or cannot yet be verified, it remains PENDING and does not increase your balance.

Use **Recover checkout** if Checkout was interrupted or the payment link is missing. Recovery tries to find or recover the original checkout safely. It does not confirm payment by itself.

Recovery is limited. An old or unresolved request may require manual investigation by the application administrator. If recovery still returns no link, keep the operation reference and ask for help rather than repeatedly starting new deposits.

### What happens if I cancel on Stripe?

Returning through Stripe's cancel page does not prove a payment succeeded or change the wallet operation to CANCELLED. The operation can remain PENDING. Use **Check payment** or **Recover checkout** to check its state.

### How should I retry a request?

When the page offers **Retry same request**, use it to retry the same intended transaction. The application keeps a request identifier to protect against duplicate processing.

**Start a new transaction** means a separate intended transaction. Do not use it as a way to retry an unknown outcome. Check history first, especially after a timeout or page reload. Do not change the amount of an unresolved request and treat it as the same transaction.

## Simulated withdrawals

In **Cash Wallet**, enter a positive USD withdrawal amount with no more than two decimal places, then choose **Withdraw cash**.

Withdrawals use available cash, not the full cash balance. For example, if cash balance is $1,000 and reserved cash is $300, you can withdraw no more than $700, subject to the configured withdrawal limit.

A successful withdrawal immediately reduces virtual cash and records a COMPLETED withdrawal. Retrying the same request does not deduct it twice. If the outcome is uncertain, check history and use the same-request retry rather than starting a separate withdrawal.

This is a simulation. It does not pay money to a card, bank account, or Stripe account, and it does not automatically sell investments to raise cash.

## Wallet history, PDF receipts, and statements

Wallet history lists your deposits and withdrawals newest first. Use pagination to view older operations. Each entry includes an operation reference that identifies that transaction.

Wallet statuses are:

| Status | Meaning |
| --- | --- |
| PENDING | The operation has not completed. A pending deposit has not credited cash. |
| COMPLETED | The deposit or withdrawal has completed and its balance change was recorded. |
| FAILED | The operation is recorded as failed, not as a successful wallet transaction. |
| CANCELLED | The operation is recorded as cancelled, not as a successful wallet transaction. |

A timeout or cancelled browser checkout does not automatically mean the wallet record is FAILED or CANCELLED. Check the status shown in history.

### How do I download a receipt?

Choose **Download PDF** beside a COMPLETED deposit or withdrawal. Receipts include the operation type, USD amount, full reference, completion time, payment method, and recorded balance after that transaction.

The receipt's balance is historical. It is not your current wallet balance. Pending or incomplete operations do not have downloadable receipts.

### How do I export a wallet statement?

Choose **From** and **To** dates above wallet history, then choose **Export Statement**.

- Both dates are included, based on when an operation completed.
- Only COMPLETED deposits and withdrawals appear in the statement table and totals.
- Totals show deposits, withdrawals, net funding, and transaction count.
- Net funding means completed deposits minus completed withdrawals in the selected period.
- The statement includes matching records across history pages and supports multiple PDF pages.
- Choose a range of at most 366 days. If it contains more than 1,000 completed operations, choose a shorter range.
- A range with no completed operations produces an empty statement with zero totals.

Statements do not claim an opening or closing balance. Dates and times are shown as recorded by the application. PDF exports do not change your balances or transactions and are educational records, not bank statements.

## Notifications

The notification bell shows live events received during your current session. Supported events include completed direct purchases and sales, executed BUY and SELL limit orders, triggered price alerts, and completed wallet deposits and withdrawals.

Trade completions, triggered price alerts, and completed wallet operations also have email confirmations. Email delivery and live notifications can be delayed or unavailable; use the relevant history or status page to check the recorded result.

The in-app notification list is not a permanent inbox and is not restored as historical notifications after a reload. **Clear notifications** clears the displayed list, not your trade or wallet history. Live notifications may be active in another browser tab instead of the current tab.

Notification delivery can occasionally repeat. A repeated confirmation does not mean a wallet operation changed the balance twice. Check the operation reference and wallet history.

## Common questions and troubleshooting

### Why can I not log in?

Check your email and password, verify your email, and make sure the account is active. If you see too many attempts, wait before retrying. Use **Forgot password?** if you cannot remember your password.

### Why is my new portfolio not available yet?

Portfolio setup follows email verification and may take a short time. Refresh after waiting. If it stays unavailable, ask the administrator to investigate. Do not register duplicate accounts to retry setup.

### Why was my buy or withdrawal rejected for insufficient funds?

Check **Available cash** in Cash Wallet. Cash reserved for pending BUY limit orders cannot be used again. If appropriate, cancel a pending order and confirm cancellation before retrying.

### Why was my sell rejected?

Check that you own the asset and have enough unreserved shares. Also check that the asset is still available for trading. Refresh holdings if they are unavailable or outdated.

### Why has my limit order not filled?

Its price condition may not have been reached, or background processing may not have completed. Refresh My Orders and inspect the status. The displayed price alone does not prove that execution succeeded.

### What should I do after a timeout?

Check the relevant order, transaction, or wallet history before submitting again. A timeout does not prove the server did nothing. For wallet requests, preserve the existing retry state and retry the same intended request where available.

### Why is my wallet balance or history outdated?

Choose **Refresh wallet**. A refresh reads the latest data; it does not create a payment, withdrawal, or new history record. If a deposit is PENDING, use **Check payment** to request payment verification.

### Why did a deposit increase portfolio value but not profit?

Deposits are additional virtual funding. The overall gain/loss calculation accounts for completed deposits and withdrawals separately from investment performance. The historical value chart can still rise when funds are deposited.

### Why did I not receive an email or live notification?

Check your email's spam folder and the notification connection message. Refresh the relevant history page to confirm the result. Notifications are not the source of truth for whether a trade or wallet operation completed.

### Why is a PDF download unavailable?

Receipts require a COMPLETED wallet operation owned by you. For statements, check your dates and use a shorter period if the date or transaction limit is exceeded. A service error may require trying the download again later.

## Educational-only limitations and safe use

- All portfolio funds, trading, deposits, and withdrawals are for demonstration and education.
- Stripe Checkout is used in sandbox mode. Use test payment details only; do not enter real card information for this demonstration.
- Assets and price charts do not provide live market quotes.
- Simulated results do not predict real investment returns.
- The application does not provide personal investment, tax, or financial advice.
- The investor interface does not manage other investors' portfolios or administrator-only functions.
- Never share passwords, login tokens, payment details, or private account information in a help question.
- A help answer is guidance only. It is not a trade instruction, payment confirmation, or permission to perform an account operation.
