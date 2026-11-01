# AFTRHRS Google Play Premium subscriptions

## App and backend integration

The native app uses Google Play Billing Library 7.1.1. It follows the backend contract already implemented in the AFTRHRS API and documented in the backend repository at `docs/native-android-billing-api.md`.

The app:

1. Reads `GET /api/v1/subscription` and `GET /api/v1/billing/google/availability` using the authenticated AFTRHRS session.
2. Queries the server configured monthly and annual product IDs from Google Play and uses each eligible offer token and Play's localized price.
3. Adds the SHA-256 hash of the AFTRHRS account UUID as `obfuscatedAccountId` to bind the purchase to the signed-in account.
4. POSTs only `{ "purchaseToken": "..." }` to `/api/v1/billing/google/verify` for purchased and restored tokens. The backend verifies with Android Publisher API, checks account binding and records entitlement.
5. Acknowledges the purchase only after the API confirms server-side verification. Pending purchases do not unlock Premium.

Product IDs are supplied by the backend through `GOOGLE_PLAY_MONTHLY_PRODUCT_ID` and `GOOGLE_PLAY_ANNUAL_PRODUCT_ID`; they must be distinct. Create those monthly and annual auto-renewing subscription products and base plans in Play Console. Set the South African prices to **R69.99 per month** and **R599.99 per year**. The app displays Play's localized catalog price when available and uses these amounts as loading fallbacks.

## External setup required before selling

The backend already implements token verification, account binding, idempotent purchase records, Google RTDN handling, stale-state reconciliation, and sandbox/production gates. It lives in the separate `Afterhours MVP` repository. Before public sales:

1. Create the monthly and annual products/base plans in Play Console and configure their exact product IDs in backend deployment secrets.
2. Configure `GOOGLE_PLAY_PACKAGE_NAME=za.co.aftrhrs`, Android Publisher API access, the Play service account credentials and authenticated Pub/Sub RTDN as specified in the backend guide.
3. Apply the backend billing migrations and configure its Google sandbox purchase switch. Keep production gates off until internal-track test purchases pass.
4. Add license testers and upload a signed AAB to an internal testing track. Verify purchase, pending payment, account binding, restore, renewal, cancellation, expiry, grace period, hold, refund/revoke and duplicate RTDN behavior.
5. Enable production only after the backend and Play Console flows have been verified.

The Android repo alone cannot create Play Console products, set prices, grant Google API access, or configure deployment secrets and Pub/Sub. Production selling remains gated until those external steps are completed.
