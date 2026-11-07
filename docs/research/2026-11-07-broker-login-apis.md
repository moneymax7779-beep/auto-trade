# Daily login and read-only checks: Upstox, Kotak Neo, Groww (researched 7 Nov 2026)

What the trader-workspace logins (`UpstoxLogin`, `KotakNeoLogin`, `GrowwLogin`) are built on.

- **Sources:** official docs and official SDK source. For Kotak Neo, `Kotak-Neo/kotak-neo-python` at commit 43d3bd1
  (3 Nov 2026); for Groww, `growwapi` 1.5.0.
- **UNVERIFIED** marks what the official material does not state. Each of those is checked on the first real login
  (plan section 10.6).
- **Order placement is not covered.** It belongs to the multi-broker plan's U and N phases.

## Upstox (v2 OAuth; v3 approve-on-phone not used yet)

1. **Authorization dialog**
   - URL: `GET https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=<API_KEY>&redirect_uri=<registered>&state=<random>`
   - Source: [authorize](https://upstox.com/developer/api-documentation/authorize/)
2. **Redirect back**
   - Upstox redirects to `<redirect_uri>?code=...&state=...`.
   - The code can be used **once only**, even if the exchange fails.
3. **Code exchange**
   - Request: `POST https://api.upstox.com/v2/login/authorization/token`, form-encoded, with
     `code, client_id, client_secret, redirect_uri, grant_type=authorization_code`.
   - Response: `access_token`, `user_id` (the UCC), `user_name`, `email`, ...
   - Expiry: the token ends **03:30 IST the next day**, whenever it was made.
   - Source: [get-token](https://upstox.com/developer/api-documentation/get-token/)
4. **Read checks** (`Authorization: Bearer`)
   - Profile: `GET /v2/user/profile` returns `data.user_id`.
   - Funds: `GET /v2/user/get-funds-and-margin` returns `data.equity.available_margin`. The funds service is down
     00:00–05:30 IST (`UDAPI100072`).
   - Sources: [get-profile](https://upstox.com/developer/api-documentation/get-profile/),
     [funds](https://upstox.com/developer/api-documentation/get-user-fund-margin/)
5. **Errors**
   - Shape: `{"status":"error","errors":[{"error_code","message",...}]}`
   - An invalid token returns `UDAPI100050`. That this comes with HTTP 401 is UNVERIFIED.
6. **Static IP:** required for **orders** only.
7. **Caution: never log in with zt-tiger-v2's Upstox app.**
   - A new login of the same app ends that app's earlier token.
   - The TOTP endpoint also revokes the app's active token.
   - Every member uses their own app.

## Kotak Neo (Trade API v2)

**Sources:**
[migration guide 3 Dec 2025](https://www.kotakneo.com/uploads/API_Migration_guide_03_12_2025_accfccef45.pdf),
[SDK](https://github.com/Kotak-Neo/kotak-neo-python),
[static IP](https://www.kotakneo.com/platform/kotak-neo-trade-api/static-ip-details/).

**Note on headers:** auth headers are sent as the plain token, without a `Bearer` prefix.

1. **One-time setup**
   - The member generates the **consumer key** in the NEO app (More → Trade API → Generate application).
   - The member registers TOTP.
2. **TOTP login**
   - Request: `POST https://mis.kotaksecurities.com/login/1.0/tradeApiLogin`
   - Headers: `Authorization: <consumer key>`, `neo-fin-key: neotradeapi`
   - Body: `{"mobileNumber":"+91...","ucc":"...","totp":"123456"}`
   - Response: `data.token` (view token), `data.sid`, `data.ucc`.
   - A 2xx answer can carry an error body with no `data`. The code checks for `data` and reads the reason from the
     whole answer.
3. **MPIN check**
   - Request: `POST .../login/1.0/tradeApiValidate`
   - Headers: `Authorization`, `sid`, `Auth: <view token>`, `neo-fin-key`
   - Body: `{"mpin":"..."}`
   - Response: `data.token` (trade token), `data.sid`, `data.baseUrl`, `data.ucc`.
4. **Limits check**
   - Request: `POST {baseUrl}/quick/user/limits`
   - Headers: `Sid`, `Auth`
   - Form: `jData={"seg":"ALL","exch":"ALL","prod":"ALL"}`
   - Response: string numbers. That `Net` is the field for available margin is UNVERIFIED.
   - HTTP 403 means the session is invalid.
5. **Session lifetime:** not published (UNVERIFIED). The app keeps the session until the end of the IST day.
6. **Static IP**
   - Since 1 Apr 2026, enforced on order APIs only.
   - "The session must be created from the same IP that is sending order requests": the login has to run on the order
     host.
   - Up to 2 IPs, changeable every 7 days.

## Groww Trade API

**Sources:** [curl docs](https://groww.in/trade-api/docs/curl),
[Python SDK docs](https://groww.in/trade-api/docs/python-sdk), `growwapi` 1.5.0 (`groww/client.py`).

1. **Base and headers**
   - Base URL: `https://api.groww.in`
   - Headers: `Authorization: Bearer <token>`, `Accept: application/json`, `X-API-VERSION: 1.0`
2. **Token, approval flow** (used here)
   - The member approves the key **daily** on Groww's API keys page.
   - Request: `POST /v1/token/api/access`
   - Headers: `Authorization: Bearer <API_KEY>`
   - Body: `{"key_type":"approval","checksum":hex(sha256(secret+timestamp)),"timestamp":"<epoch s>"}`. The checksum is
     valid 10 minutes.
   - Response: `token`, `expiry` (a local time without a zone), `isActive`.
   - Limit: 150 token requests a day.
3. **TOTP flow:** exists (`key_type: totp`). Whether it needs daily approval is UNVERIFIED (the docs contradict each
   other). It is not used.
4. **Read checks**
   - User: `GET /v1/user/detail` returns `payload.ucc`.
   - Margins: `GET /v1/margins/detail/user` returns `payload.fno_margin_details.option_buy_balance_available`.
5. **Errors**
   - Shape: `{"status":"FAILURE","error":{"code","message"}}`
   - The token endpoint answers 400 with `error.displayMessage`.
6. **Static IP:** required for orders.
7. **Price:** ₹499 a month (early-bird price, as of Nov 2026).

## What the server stores

| Broker | Typed each day | Stored (write-only file store) | Passed through only |
|---|---|---|---|
| Zerodha | login at Kite | API key, API secret | — |
| Upstox | login at Upstox | API key, API secret | — |
| Kotak Neo | TOTP + MPIN on our page | consumer key, mobile number (masked on the page) | TOTP, MPIN |
| Groww | approve on Groww | API key, API secret | — |
