#!/usr/bin/env python3
"""Builds catalog.json: the most popular shows on each streaming service.

The app shows these on each service's page and on Home. The list comes from
TVmaze's public show index (https://www.tvmaze.com/api, licensed CC BY-SA),
read from start to finish, so it is built here once a day instead of on every
phone. Usage: build-catalog.py OUTPUT_FILE
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request

# Service ids as the app knows them, with the names TVmaze uses for each and the
# website a show's own page must be on to be linked. Keep in step with Services.kt.
SERVICES = {
    "netflix": (["netflix"], "netflix.com"),
    "hbomax": (["hbo max", "max", "hbo"], "hbomax.com"),
    "prime": (["prime video", "amazon prime video", "amazon video"], "primevideo.com"),
    "disney": (["disney+", "disney plus"], "disneyplus.com"),
    "hulu": (["hulu"], "hulu.com"),
    "appletv": (["apple tv+", "apple tv", "apple tv plus"], "tv.apple.com"),
    "peacock": (["peacock", "peacock premium"], "peacocktv.com"),
    "paramount": (["paramount+", "paramount plus"], "paramountplus.com"),
    "youtube": (["youtube", "youtube premium"], "youtube.com"),
    "tubi": (["tubi", "tubi tv"], "tubitv.com"),
    "pluto": (["pluto tv"], "pluto.tv"),
}
SKIPPED_TYPES = {"News", "Talk Show", "Sports", "Award Show", "Panel Show", "Game Show", "Variety"}
PER_SERVICE = 200


def fetch(url):
    for attempt in range(5):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "SafeWatch catalog builder"})
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None  # past the last page
            if error.code == 429:
                time.sleep(5)  # asked to slow down
                continue
            raise
        except (urllib.error.URLError, TimeoutError):
            time.sleep(5)
    raise RuntimeError(f"gave up on {url}")


def service_for(show):
    for holder in ("webChannel", "network"):
        name = ((show.get(holder) or {}).get("name") or "").strip().lower()
        for service, (names, _) in SERVICES.items():
            if name in names:
                return service
    return None


def plain(html):
    text = re.sub(r"<[^>]*>", "", html or "").replace("&amp;", "&").replace("&quot;", '"').replace("&#39;", "'").strip()
    return text if len(text) <= 320 else text[:317].rsplit(" ", 1)[0] + "..."


def main(out_path):
    by_service = {service: [] for service in SERVICES}
    page = 0
    while True:
        shows = fetch(f"https://api.tvmaze.com/shows?page={page}")
        if shows is None:
            break
        for show in shows:
            service = service_for(show)
            image = (show.get("image") or {}).get("original")
            if not service or not image or show.get("type") in SKIPPED_TYPES:
                continue
            site = show.get("officialSite") or ""
            host = re.sub(r"^https?://([^/]+).*$", r"\1", site)
            by_service[service].append({
                "weight": show.get("weight") or 0,
                "rating": (show.get("rating") or {}).get("average") or 0,
                "id": f"tvmaze:{show['id']}",
                "name": show.get("name") or "",
                "year": (show.get("premiered") or "")[:4],
                "poster": image,
                "overview": plain(show.get("summary")),
                "link": site if host.endswith(SERVICES[service][1]) else None,
                "genres": show.get("genres") or [],
            })
        page += 1
        time.sleep(0.6)  # TVmaze allows 20 requests every 10 seconds
    catalog = {}
    for service, shows in by_service.items():
        shows.sort(key=lambda s: (s["weight"], s["rating"]), reverse=True)
        catalog[service] = [{k: v for k, v in s.items() if k not in ("weight", "rating")} for s in shows[:PER_SERVICE]]
        print(f"{service}: {len(shows)} shows found, {len(catalog[service])} kept")
    if sum(len(v) for v in catalog.values()) < 200:
        raise RuntimeError("far fewer shows than expected; not publishing")
    with open(out_path, "w") as out:
        json.dump({"built": time.strftime("%Y-%m-%d"), "source": "TVmaze (CC BY-SA)", "services": catalog}, out, separators=(",", ":"))
    print(f"read {page} pages")


if __name__ == "__main__":
    main(sys.argv[1])
