"""Writes the two texts the Release workflow publishes, from what tools/release_text.py already knows.

  python .github/scripts/release_ci.py notes --version 0.11.0 --repository owner/repo
        --commit <sha> --run-url <url> --out notes.md
  python .github/scripts/release_ci.py index --version 0.11.0 --repository owner/repo
        --created 2026-10-12T05:00:00Z --commit <sha> --out patches-bundle.json

notes   The GitHub release notes: release_text.py's github-notes, with the links pointed at this
        repository and a "What was checked" paragraph that says what CI checked and nothing more.
index   patches-bundle.json for the release, which Morphe Manager reads to find the newest bundle,
        so download_url points at this repository's release.

There are no Facebook APKs on the runner and the workflow runs no tests, so neither text claims the
patches were applied to a build or that tests passed. README.md, the bug form and the rest of
release_text.py stay as upstream has them: the Release workflow only writes patches-bundle.json.
"""

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import release_text  # noqa: E402  (tools/ is on the path only from here)


def check_arguments(args) -> None:
    if not release_text.VERSION.match(args.version):
        release_text.fail(f"{args.version} isn't X.Y.Z")
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repository):
        release_text.fail(f"{args.repository} isn't owner/repo")
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        release_text.fail(f"{args.commit} isn't a full commit hash")


def checked_paragraph(args, newest: str) -> str:
    bundle = f"patches-{args.version}.mpp"
    # "targets Facebook <build>" is the phrase the app's update check reads the target from
    # (release_text.TARGET_PHRASE), so the build is named once, the newest one.
    return "\n".join([
        f"* Built in [this workflow run]({args.run_url}) from a clean checkout of commit {args.commit[:12]}.",
        f"* This release targets Facebook {newest}, the newest build patches-list.json declares.",
        "* The bundle carries classes.dex, its timestamp is pinned to the commit, its manifest says the source "
        "was clean, and verifyBundle matched it against patches-list.json.",
        f"* Build provenance is attested. Check a download with `gh attestation verify {bundle} -R {args.repository}`.",
        "* No release receipt and no tests: the runner has no Facebook APKs, so the patches weren't applied to "
        "one here, and the workflow doesn't run the test suites.",
    ])


def notes(args) -> None:
    check_arguments(args)
    # The links in the notes name release_text.REPOSITORY, which is where upstream publishes.
    release_text.REPOSITORY = args.repository
    _, targets = release_text.catalog_targets(ROOT)
    text, said = release_text.github_notes(ROOT, args.version, checked_paragraph(args, targets[0][0]))
    release_text.write(Path(args.out), text)
    print(f"[release] {said}")


def summary_of(version: str) -> str:
    """The Facebook bullet `cut` put first in the version's CHANGELOG section, as plain text."""
    text = release_text.read(ROOT / "CHANGELOG.md")
    heading = re.search(rf"^## {re.escape(version)} \(\d{{4}}-\d{{2}}-\d{{2}}\)$", text, re.MULTILINE)
    if heading is None:
        release_text.fail(f"CHANGELOG has no dated {version} heading")
    start, end = release_text.section_bounds(text, heading.group(0)[3:])
    loose, _ = release_text.parse_section(text[start:end], version)
    if len(loose) != 1 or not loose[0].startswith("* **Facebook:** "):
        release_text.fail(f"the {version} section has to open with the one Facebook summary bullet cut writes")
    return release_text.plain(release_text.BULLET.match(loose[0]).group("text")).replace("`", "")


def index(args) -> None:
    check_arguments(args)
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z?", args.created):
        release_text.fail(f"{args.created} isn't a UTC time written YYYY-MM-DDTHH:MM:SS")

    count, targets = release_text.catalog_targets(ROOT)
    newest, codes = targets[0]
    older = [version for version, _ in targets[1:]]

    first = f"Hushfacebook v{args.version} targets Facebook {newest} ({release_text.PACKAGE}), the newest release"
    first += f", and {release_text.spoken(older)} still work." if older else "."
    patch_line = f"Patch the arm64-v8a bundle of Facebook {newest}"
    if "ARM64_V8A" in codes:
        patch_line += f", build {codes['ARM64_V8A']}"
    if "ARMEABI_V7A" in codes:
        patch_line += f", or its 32-bit build {codes['ARMEABI_V7A']}"
    patch_line += "."
    if older:
        builds = [f"{v} ({next(iter(c.values()))})" if c else v for v, c in targets[1:]]
        patch_line += f" Build{'s' if len(older) > 1 else ''} {release_text.spoken(builds)} still work{'' if len(older) > 1 else 's'}."

    summary = summary_of(args.version)
    # The gate reads the patch count out of the description, so it is said once if the summary doesn't.
    counted = "" if f"{count} patches" in summary else f"This release has {count} patches. "
    description = "\n\n".join([
        first,
        summary,
        f"{counted}Built by GitHub Actions from commit {args.commit[:12]}, with build provenance attested. "
        "The patches weren't applied to a Facebook APK in CI.",
        "Review diagnostic reports for private data before sharing them.",
        f"Needs Morphe Manager {release_text.manager_floor(ROOT)} or newer. {patch_line} The .mpp is a patch bundle, "
        "not a Facebook APK. Keep the manager's existing signing key when updating.",
    ])
    if any(dash in description for dash in release_text.DASHES):
        release_text.fail("the description has an em or en dash")
    # The facts the release gate (scripts/validate-release-facts.ps1) reads out of a description.
    counts, builds = release_text.facts_in(description)
    if counts != {str(count)} or builds != {newest}:
        release_text.fail(f"the description has to name {count} patches and Facebook {newest} alone, and it names "
                          f"{', '.join(sorted(counts)) or 'no'} patch counts and Facebook {', '.join(sorted(builds))}. "
                          "Check the CHANGELOG summary.")

    index_path = ROOT / "patches-bundle.json"
    bundle = json.loads(release_text.read(index_path))
    bundle.update({
        # The format the index has always used: UTC, to the second, no zone suffix.
        "created_at": args.created.rstrip("Z"),
        "description": description,
        "download_url": f"https://github.com/{args.repository}/releases/download/"
                        f"v{args.version}/patches-{args.version}.mpp",
        "version": args.version,
    })
    release_text.write(Path(args.out), json.dumps(bundle, indent=2, ensure_ascii=False) + "\n")
    print(f"[release] index points at {args.repository} v{args.version} ({count} patches, Facebook {newest})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for name, function, extra in (("notes", notes, ("--run-url",)), ("index", index, ("--created",))):
        p = sub.add_parser(name)
        p.set_defaults(run=function)
        for option in ("--version", "--repository", "--commit", "--out") + extra:
            p.add_argument(option, required=True)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
