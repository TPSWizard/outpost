# Deploying conzahub.com/prepper

The page is `index.html` (one file, no dependencies, follows the phone's light/dark mode).

The APKs are 34 MB and 78 MB. Cloudflare Workers static assets are capped at 25 MiB per file, so the APKs
go in an **R2 bucket** and the Worker streams them. R2 egress is free.

## 1. Put the APKs in R2

```bash
npx wrangler r2 bucket create conzahub-downloads
npx wrangler r2 object put conzahub-downloads/prepper/outpost-1.3.0-phone-arm64.apk \
  --file outpost-1.3.0-phone-arm64.apk --content-type application/vnd.android.package-archive --remote
npx wrangler r2 object put conzahub-downloads/prepper/outpost-1.3.0-universal.apk \
  --file outpost-1.3.0-universal.apk --content-type application/vnd.android.package-archive --remote
```

## 2. Bind the bucket (wrangler.jsonc / wrangler.toml)

```jsonc
"r2_buckets": [{ "binding": "DOWNLOADS", "bucket_name": "conzahub-downloads" }]
```

Put `index.html` at `prepper/index.html` in your static assets folder, so `/prepper/` serves it.

## 3. Route the APKs in your Worker's fetch handler

Add this before your existing routing:

```js
const url = new URL(request.url);
if (url.pathname.startsWith("/prepper/") && url.pathname.endsWith(".apk")) {
  const key = url.pathname.slice(1); // "prepper/outpost-1.3.0-phone-arm64.apk"
  const obj = await env.DOWNLOADS.get(key, { range: request.headers, onlyIf: request.headers });
  if (!obj) return new Response("Not found", { status: 404 });
  const headers = new Headers();
  obj.writeHttpMetadata(headers);
  headers.set("Content-Type", "application/vnd.android.package-archive");
  headers.set("Content-Disposition", `attachment; filename="${key.split("/").pop()}"`);
  headers.set("Cache-Control", "public, max-age=86400");
  headers.set("ETag", obj.httpEtag);
  headers.set("Accept-Ranges", "bytes");
  if (obj.range) {
    const { offset = 0, length = obj.size - offset } = obj.range;
    headers.set("Content-Range", `bytes ${offset}-${offset + length - 1}/${obj.size}`);
    return new Response(obj.body, { status: 206, headers });
  }
  return new Response("body" in obj ? obj.body : null, { status: "body" in obj ? 200 : 304, headers });
}
if (url.pathname === "/prepper") return Response.redirect(url.origin + "/prepper/", 301);
```

Range support lets phones resume an interrupted download.

## 4. Releasing an update

Upload the new APKs with the new version in the name, then update the three places in `index.html` that say
`1.3.0`: the two download links, the version line, and the SHA-256 table (`sha256sum *.apk`).

## Before you share the link publicly

- **Signing key.** These builds are signed with the development (debug) key. Switch to your own release key
  before sharing; otherwise everyone who installs now has to uninstall to get later updates.
- **Source link.** Outpost is GPLv3, so the page links to `github.com/TPSWizard/outpost`. Push the repo
  there (the source zip includes the git history) before the link goes live.
