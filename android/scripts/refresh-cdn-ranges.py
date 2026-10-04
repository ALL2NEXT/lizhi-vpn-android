"""Refresh the packaged CDN-only IP catalog; run from any working directory.

ProjectDiscovery's Google list contains general Google address space rather than
CDN-specific ranges, so it is deliberately excluded. Cloudflare uses its official
proxy ranges rather than its full ASN. General hosting/cloud ranges are excluded.
"""

import datetime
import json
import pathlib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "app/src/main/resources"
BASE = "https://raw.githubusercontent.com/projectdiscovery/cdncheck/"


def download(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read().decode("utf-8")


def main():
    commit = json.loads(download(
        "https://api.github.com/repos/projectdiscovery/cdncheck/commits/main"
    ))["sha"]
    source = json.loads(download(BASE + commit + "/sources_data.json"))
    names = {"cloudfront": "Amazon CloudFront", "fastly": "Fastly", "gcore": "Gcore",
             "arvancloud": "ArvanCloud", "cdnetworks": "CDNetworks", "gocache": "GoCache",
             "huawei": "华为云 CDN", "qrator": "Qrator"}
    providers = {names.get(name, name): sorted(set(ranges))
                 for name, ranges in source["cdn"].items() if name != "google"}
    providers["Imperva"] = sorted(set(source["waf"]["incapsula"]))
    cloudflare_sources = ["https://api.cloudflare.com/client/v4/ips"]
    cloudflare = json.loads(download(cloudflare_sources[0]))["result"]
    providers["Cloudflare"] = cloudflare["ipv4_cidrs"] + cloudflare["ipv6_cidrs"]
    data = {"updated": datetime.datetime.now(datetime.timezone.utc).date().isoformat(),
            "sources": [BASE + commit + "/sources_data.json", *cloudflare_sources],
            "providers": providers}
    license_text = download(BASE + commit + "/LICENSE.md")
    RESOURCES.mkdir(parents=True, exist_ok=True)
    (RESOURCES / "cdn-ip-ranges.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (RESOURCES / "cdn-ip-ranges-LICENSE.txt").write_text(license_text, encoding="utf-8")
    print(f"Saved {len(providers)} providers, {sum(map(len, providers.values()))} ranges.")


if __name__ == "__main__":
    main()
