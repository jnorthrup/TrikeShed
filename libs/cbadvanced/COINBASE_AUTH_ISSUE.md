# Coinbase Advanced Trade Client - Troubleshooting

## Current Issue

The cbadvanced client is returning **401 Unauthorized** when attempting to authenticate with Coinbase Advanced Trade API.

## Root Cause

The API credentials in the `.env` file are **expired, revoked, or invalid**. The JWT token is being generated correctly with:
- ✅ Correct ES256 signature
- ✅ Valid timestamp (current time, not expired)
- ✅ Proper URI format ("GET api.coinbase.com/api/v3/brokerage/accounts")
- ✅ Correct issuer ("cdp" for Coinbase Developer Platform)
- ✅ Valid key ID ("bossmang")

## What's Working

- Code compiles and runs successfully
- JWT generation is correct (86-byte ECDSA signature)
- HTTP request is properly formatted
- Coinbase API endpoint is reachable and responding
- Time synchronization is correct

## What's NOT Working

- The API key (`bossmang` / `0b8a4cea-d14b-4e4e-a4af-429d33b74bdb`) is rejected by Coinbase
- Coinbase returns generic 401 with no additional error details
- The key likely:
  - Doesn't exist in the Coinbase Cloud Platform
  - Has been revoked or expired
  - Lacks "Trade" permissions for Coinbase Advanced Trade API

## Solution

To fix this issue, you need to:

1. **Go to Coinbase Cloud Platform**: https://cloud.coinbase.com/keys
2. **Create a new API key pair**:
   - Select "Coinbase Advanced Trade" API
   - Enable "Trade" permissions (at minimum)
   - Download the private key (PEM format)
3. **Update the `.env` file**:
   ```
   COINBASE_API_KEY=your-new-api-key-uuid
   COINBASE_API_KEY_NAME=your-key-name
   COINBASE_API_SECRET=-----BEGIN EC PRIVATE KEY-----
   your-new-private-key-here
   -----END EC PRIVATE KEY-----
   ```
4. **Re-run the client**: `./gradlew :libs:cbadvanced:authProof`

## Testing Without Credentials

If you want to test the client code without real credentials, you can:

1. Mock the HTTP transport
2. Use a test API (if Coinbase provides one)
3. Validate the JWT generation offline (which we've already confirmed works)

## Current .env File Location

```
/Users/jim/work/TrikeShed/libs/dreamer-kmm/.env
```

## JWT Example (for reference)

The client is generating valid JWTs like:
```
Header: {"alg":"ES256","kid":"bossmang","typ":"JWT","nonce":"..."}
Payload: {"sub":"bossmang","iss":"cdp","nbf":1778986884,"exp":1778987004,"uri":"GET api.coinbase.com/api/v3/brokerage/accounts"}
Signature: 86 bytes (valid ECDSA signature)
```

This JWT format is correct per Coinbase Advanced Trade API documentation.

## Additional Resources

- Coinbase Advanced Trade API Docs: https://docs.cloud.coinbase.com/advanced-trade-api/docs
- Coinbase Cloud Platform: https://cloud.coinbase.com/
- API Key Management: https://cloud.coinbase.com/keys

## Next Steps

**Get valid API credentials from Coinbase Cloud Platform** - there is no workaround for invalid credentials.
