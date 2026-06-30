from __future__ import annotations

import argparse

from skilllib import all_asset_definitions, asset_is_fresh, generate_asset, load_registry, rel


def main() -> int:
    parser = argparse.ArgumentParser(description="refresh generated skills knowledge")
    parser.add_argument("--asset", action="append", default=[])
    parser.add_argument("--skill", action="append", default=[])
    parser.add_argument("--changed-only", action="store_true")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args()

    registry = load_registry()
    assets = all_asset_definitions(registry)
    if args.asset:
        assets = [asset for asset in assets if asset.asset_id in set(args.asset)]
    if args.skill:
        assets = [asset for asset in assets if asset.skill in set(args.skill)]

    if args.list:
        for asset in assets:
            print(f"{asset.asset_id} -> {rel(asset.output)}")
        return 0

    refreshed = []
    for asset in assets:
        if args.changed_only and asset_is_fresh(asset.asset_id, registry):
            continue
        output = generate_asset(asset.asset_id, registry)
        refreshed.append(rel(output))

    for item in refreshed:
        print(item)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

