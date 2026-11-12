# VYBE Website

Static multi-page Material 3 website for VYBE. Deploy this folder as the Vercel Root Directory.

## Main pages
- Home: `index.html`
- Features: `features.html`
- Download: `download.html`
- Help: `help.html`
- About: `about.html`
- Privacy: `privacy.html`
- Terms: `terms.html`
- Contact: `contact.html`
- Changelog: `changelog.html`

## Search / SEO files
- `sitemap.xml`
- `robots.txt`
- `site.webmanifest`
- `404.html`
- `vercel.json` security headers

Canonical base URL is currently `https://vybetune.vercel.app`. If you connect a custom domain later, replace this base URL in all HTML canonical/OG tags plus `sitemap.xml` and `robots.txt`.

The site fetches the latest GitHub Release client-side and points all `[data-download]` buttons to the newest universal Android APK when available.

## Latest content update
- Pricing wording is future-proofed: permanent pricing claims were removed, including fixed $0 structured-data offers.
- Added visible “iPhone — Coming soon” platform messaging on Home, Download, About, and the shared footer.
