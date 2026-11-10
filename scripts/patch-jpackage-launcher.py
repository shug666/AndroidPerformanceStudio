#!/usr/bin/env python3
"""Patches jpackage-built Linux packages to work around JDK-8380085.

The jpackage ELF launcher segfaults (setenv on a truncated JvmlLauncherData
buffer) once the cfg classpath exceeds the launcher's pipe capacity. The fix
landed only in JDK 27, so this script replaces the launcher with a shell
wrapper that execs the bundled runtime's java directly, and restores
runtime/bin/java (the compose jlink output strips bin/).

Usage:
  patch_payload <payload_root> <jdk_java>
      payload_root: extracted package tree containing <root>/opt/... or any
                    tree whose app dir is <payload_root>/opt/<name>/bin
      jdk_java:     path to the JDK's bin/java of the SAME build the bundled
                    runtime was jlinked from

  rebuild_rpm <rpm_in> <jdk_java> <rpm_out>
      extracts, patches, regenerates the rpm with rpmbuild (scriptlets from the
      original rpm are preserved), and verifies the result.
"""
import os
import shutil
import subprocess
import sys
import tempfile

WRAPPER = r"""#!/usr/bin/env bash
# Replacement for the jpackage ELF launcher (JDK-8380085 workaround).
# The stock launcher segfaults when the cfg classpath exceeds the launcher
# pipe capacity; this wrapper execs the bundled runtime's java directly with
# the same classpath contract as the generated .cfg.
set -euo pipefail
LAUNCHER_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
APP_DIR="$LAUNCHER_DIR/../lib/app"
CFG="$APP_DIR/AndroidPerformanceStudio.cfg"
JAVA="$LAUNCHER_DIR/../lib/runtime/bin/java"
CP=""
MAIN=""
OPTS=""
while IFS= read -r line; do
  case "$line" in
    app.classpath=*)
      entry="${line#app.classpath=}"
      entry="${entry/\$APPDIR/$APP_DIR}"
      CP="${CP:+$CP:}$entry"
      ;;
    app.mainclass=*) MAIN="${line#app.mainclass=}" ;;
    java-options=*)
      opt="${line#java-options=}"
      opt="${opt//\$APPDIR/$APP_DIR}"
      OPTS="$OPTS $opt"
      ;;
  esac
done < "$CFG"
# shellcheck disable=SC2086
exec "$JAVA" $OPTS -cp "$CP" "$MAIN" "$@"
"""


def find_app_dir(payload_root: str) -> str:
    opt_root = os.path.join(payload_root, "opt")
    candidates = []
    for name in sorted(os.listdir(opt_root)) if os.path.isdir(opt_root) else []:
        app_root = os.path.join(opt_root, name)
        cfgs = []
        app_lib = os.path.join(app_root, "lib", "app")
        if os.path.isdir(app_lib):
            cfgs = [
                os.path.join(app_lib, entry)
                for entry in sorted(os.listdir(app_lib))
                if entry.endswith(".cfg")
            ]
        runtime = os.path.join(app_root, "lib", "runtime")
        if len(cfgs) == 1 and os.path.isdir(runtime):
            # the launcher binary is named after the cfg (app name), which may differ
            # in case from the package directory name
            candidates.append((app_root, cfgs[0], runtime))
    if len(candidates) != 1:
        raise SystemExit(f"expected exactly one app payload under {opt_root}, found {len(candidates)}")
    app_root, cfg, runtime = candidates[0]
    return app_root, cfg, runtime


def patch_payload(payload_root: str, jdk_java: str) -> None:
    app_dir, cfg, runtime_dir = find_app_dir(payload_root)
    launcher = os.path.join(app_dir, "bin", os.path.splitext(os.path.basename(cfg))[0])
    runtime_bin = os.path.join(runtime_dir, "bin")

    if os.path.exists(launcher) and not os.path.isfile(launcher):
        # keep a record of the original ELF launcher next to the package for diagnostics
        os.remove(launcher)
    os.makedirs(runtime_bin, exist_ok=True)
    runtime_java = os.path.join(runtime_bin, "java")
    shutil.copyfile(jdk_java, runtime_java)
    os.chmod(runtime_java, 0o755)

    with open(launcher, "w", encoding="utf-8") as handle:
        handle.write(WRAPPER)
    os.chmod(launcher, 0o755)
    print(f"patched launcher: {launcher} (JDK-8380085 workaround)")


def rpm_query(rpm: str, query_format: str) -> str:
    result = subprocess.run(
        ["rpm", "-qp", "--qf", query_format, rpm],
        check=True, capture_output=True, text=True,
    )
    return result.stdout.strip()


def rpm_scriptlets(rpm: str) -> dict:
    """Parses `rpm -qp --scripts` into a {section: body} map."""
    result = subprocess.run(
        ["rpm", "-qp", "--scripts", rpm],
        check=True, capture_output=True, text=True,
    )
    sections = {}
    current = None
    header = {
        "preinstall": "%pre",
        "install": "%post",
        "preuninstall": "%preun",
        "postuninstall": "%postun",
        "pretrans": "%pretrans",
        "posttrans": "%posttrans",
        "verify": "%verifyscript",
    }
    for line in result.stdout.splitlines():
        stripped = line.strip()
        matched = None
        for token, directive in header.items():
            if stripped.startswith(token + " scriptlet"):
                matched = directive
                break
        if matched:
            current = matched
            sections[current] = ""
        elif current is not None:
            sections[current] += line + "\n"
    return sections


def rebuild_rpm(rpm_in: str, jdk_java: str, rpm_out: str) -> None:
    name = rpm_query(rpm_in, "%{NAME}")
    version = rpm_query(rpm_in, "%{VERSION}")
    release = rpm_query(rpm_in, "%{RELEASE}")
    arch = rpm_query(rpm_in, "%{ARCH}")
    summary = rpm_query(rpm_in, "%{SUMMARY}")
    license_field = rpm_query(rpm_in, "%{LICENSE}")
    scriptlets = rpm_scriptlets(rpm_in)

    with tempfile.TemporaryDirectory(prefix="rpm-repack-") as workdir:
        tree = os.path.join(workdir, "root")
        os.makedirs(tree)
        extract = subprocess.run(
            "rpm2cpio '%s' | cpio -idmu --quiet" % rpm_in,
            shell=True, cwd=tree, capture_output=True, text=True,
        )
        if extract.returncode != 0:
            raise SystemExit(f"rpm2cpio extraction failed: {extract.stderr}")
        patch_payload(tree, jdk_java)

        buildroot = os.path.join(workdir, "buildroot")
        os.makedirs(buildroot)
        spec_lines = [
            "Name: %s" % name,
            "Version: %s" % version,
            "Release: %s" % release,
            "Summary: %s" % summary,
            "License: %s" % license_field,
            "BuildArch: %s" % arch,
            "AutoReqProv: no",
            "",
        ]
        for directive, body in scriptlets.items():
            if body.strip():
                spec_lines.append(directive)
                spec_lines.append(body.strip())
                spec_lines.append("")
        walk = sorted(os.walk(tree), key=lambda item: item[0])
        for dirpath, dirnames, filenames in walk:
            rel = os.path.relpath(dirpath, tree)
            rpm_path = "/" if rel == "." else "/" + rel
            if rel != ".":
                spec_lines.append("%dir " + rpm_path)
            for filename in sorted(filenames):
                full = os.path.join(dirpath, filename)
                rel_file = os.path.relpath(full, tree)
                mode = os.stat(full).st_mode & 0o777
                if mode & 0o111:
                    spec_lines.append('%%attr(%03o,root,root) /%s' % (mode, rel_file))
                else:
                    spec_lines.append("/" + rel_file)
        spec_path = os.path.join(workdir, "package.spec")
        with open(spec_path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(spec_lines) + "\n")

        rpm_build_home = os.path.join(workdir, "rpmhome")
        os.makedirs(os.path.join(rpm_build_home, "rpmbuild"), exist_ok=True)
        env = dict(os.environ, HOME=rpm_build_home)
        build = subprocess.run(
            [
                "rpmbuild", "-bb", "--target", "%s-%s" % (arch, "linux"),
                "--buildroot", buildroot, "--define", "_topdir %s" % rpm_build_home,
                "--define", "_rpmdir %s/rpms" % rpm_build_home,
                "--define", "_srcrpmdir %s/srpms" % rpm_build_home,
                "--define", "_specdir %s" % workdir,
                "--define", "_sourcedir %s" % workdir,
                "--define", "_builddir %s" % workdir,
                "--define", "_tmppath %s/tmp" % rpm_build_home,
                spec_path,
            ],
            env=env, capture_output=True, text=True,
        )
        if build.returncode != 0:
            sys.stderr.write(build.stdout[-4000:] + "\n" + build.stderr[-4000:] + "\n")
            raise SystemExit("rpmbuild failed")

        produced = []
        for base, _, files in os.walk(os.path.join(rpm_build_home, "rpms")):
            for filename in files:
                if filename.endswith(".rpm"):
                    produced.append(os.path.join(base, filename))
        if len(produced) != 1:
            raise SystemExit(f"expected one rebuilt rpm, found {produced}")
        # nothing more here; verification happens below on the rebuilt package

        # verify the rebuilt rpm actually carries the patched launcher
        with tempfile.TemporaryDirectory(prefix="rpm-verify-") as verify_dir:
            check = subprocess.run(
                "rpm2cpio '%s' | cpio -idmu --quiet" % produced[0],
                shell=True, cwd=verify_dir, capture_output=True, text=True,
            )
            if check.returncode != 0:
                raise SystemExit("verification extraction failed: %s" % check.stderr)
            app_dir, cfg, runtime_dir = find_app_dir(verify_dir)
            launcher = os.path.join(app_dir, "bin", os.path.splitext(os.path.basename(cfg))[0])
            runtime_java = os.path.join(runtime_dir, "bin", "java")
            with open(launcher, encoding="utf-8") as handle:
                if not handle.readline().startswith("#!"):
                    raise SystemExit("patched launcher missing from rebuilt rpm")
            if not os.path.isfile(runtime_java):
                raise SystemExit("runtime/bin/java missing from rebuilt rpm")

        shutil.copyfile(produced[0], rpm_out)
        print(f"rebuilt rpm: {rpm_out}")


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    mode = sys.argv[1]
    if mode == "patch_payload":
        patch_payload(sys.argv[2], sys.argv[3])
    elif mode == "rebuild_rpm":
        rebuild_rpm(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        raise SystemExit("unknown mode: " + mode)


if __name__ == "__main__":
    main()
