# Deploying conzahub.com/outpost

The page is `index.html` (one file, no dependencies, follows the phone's light/dark mode).

The APKs are 34 MB and 78 MB. Cloudflare Workers static assets are capped at 25 MiB per file, so the APKs
go in an **R2 bucket** and the Worker streams them. R2 egress is free.

## 1. Put the APKs in R2

```bash
npx wrangler r2 bucket create conzahub-downloads
npx wrangler r2 object put conzahub-downloads/outpost/outpost-1.3.1-phone-arm64.apk \
  --file outpost-1.3.1-phone-arm64.apk --content-type application/vnd.android.package-archive --remote
npx wrangler r2 object put conzahub-downloads/outpost/outpost-1.3.1-universal.apk \
  --file outpost-1.3.1-universal.apk --content-type application/vnd.android.package-archive --remote
```

## 2. Bind the bucket (wrangler.jsonc / wrangler.toml)

```jsonc
"r2_buckets": [{ "binding": "DOWNLOADS", "bucket_name": "conzahub-downloads" }]
```

Put `index.html` at `outpost/index.html` in your static assets folder, so `/outpost/` serves it.

## 3. Route the APKs in your Worker's fetch handler

This is the handler the live site runs. Add `serveApk` to the Worker and call it before falling through to
static assets:

```js
// The APKs are past the 25 MiB static-asset limit, so they come from R2 when DOWNLOADS is bound.
// Range requests are honoured so a phone that loses signal mid-download resumes instead of starting over.
function apkRequest(url) {
  return url.pathname.startsWith("/outpost/") && url.pathname.endsWith(".apk");
}

async function serveApk(request, bucket, url) {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("method not allowed", { status: 405 });
  }
  const key = decodeURIComponent(url.pathname.slice(1)); // "outpost/outpost-1.3.1-phone-arm64.apk"
  const object = await bucket.get(key, { range: request.headers, onlyIf: request.headers });
  if (!object) return new Response("not found", { status: 404 });

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("content-type", "application/vnd.android.package-archive");
  headers.set("content-disposition", `attachment; filename="${key.split("/").pop()}"`);
  headers.set("etag", object.httpEtag);
  headers.set("accept-ranges", "bytes");
  headers.set("cache-control", "public, max-age=86400");

  if (!("body" in object)) return new Response(null, { status: 304, headers });
  const body = request.method === "HEAD" ? null : object.body;
  // R2 reports a range even when the whole object came back, so the request decides 206 versus 200.
  if (request.headers.has("range") && object.range && "offset" in object.range) {
    const offset = object.range.offset ?? 0;
    const length = object.range.length ?? object.size - offset;
    headers.set("content-range", `bytes ${offset}-${offset + length - 1}/${object.size}`);
    return new Response(body, { status: 206, headers });
  }
  return new Response(body, { headers });
}

// In fetch(request, env):
const url = new URL(request.url);
if (env.DOWNLOADS && apkRequest(url)) return serveApk(request, env.DOWNLOADS, url);
return env.ASSETS.fetch(request);
```

Range support lets phones resume an interrupted download.

## 4. Releasing an update

Upload the new APKs with the new version in the name, then update the three places in `index.html` that say
`1.3.1`: the two download links, the version line, and the SHA-256 table (`sha256sum *.apk`).

## Before you share the link publicly

- **Signing key.** These builds are signed with the development (debug) key. Switch to your own release key
  before sharing; otherwise everyone who installs now has to uninstall to get later updates.
- **Source link.** Outpost is GPLv3, so the page links to `github.com/TPSWizard/outpost`. Push the repo
  there (the source zip includes the git history) before the link goes live.
