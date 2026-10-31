#!/usr/bin/env node
// One-shot batch converter: @tabler/icons outlined SVG -> Android VectorDrawable.
//
// Reads scripts/tabler/icons.json (drawable name -> tabler icon name), downloads the
// @tabler/icons tarball from the npm registry ONCE, extracts it with the system `tar`
// (Git Bash ships GNU tar) into the OS temp dir, converts each requested outline SVG to
// a stroke-based VectorDrawable and writes app/src/main/res/drawable/<drawable>.xml.
//
// Pure Node (no npm deps). Idempotent: re-run any time; add a line to icons.json to top up.
//
// Visual rules honored (docs/prd/mobile-app.md §8.3 / §8.4): single-color stroke, no fill
// blocks, strokeWidth=2, 24dp viewport. strokeColor is written as opaque black #FF000000
// so Compose can re-tint it with the semantic color at the use site (no android:tint here).

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "..", "..");
const ICONS_JSON = path.join(HERE, "icons.json");
const OUT_DIR = path.join(REPO_ROOT, "app", "src", "main", "res", "drawable");

const REGISTRY_PKG = "https://registry.npmjs.org/@tabler/icons";
const STROKE_COLOR = "#FF000000";
const SVG_CANDIDATE_DIRS = [
  path.join("icons", "outline"), // @tabler/icons >= ~3.x layout
  path.join("svg", "outlined", "default"), // older layout fallback
];

// ---- tiny helpers ----------------------------------------------------------

function toMsys(winPath) {
  // GNU tar from Git Bash rejects `C:\...` (treats it as host:path). Convert the
  // Windows path we hand to node:fs into its MSYS mount form /c/... which tar accepts.
  const fwd = winPath.split(path.sep).join("/");
  const m = fwd.match(/^([A-Za-z]):\/(.*)$/);
  return m ? "/" + m[1].toLowerCase() + "/" + m[2] : fwd;
}

function xmlEscape(s) {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function getAttr(tag, name) {
  const m = tag.match(new RegExp(`(?:^|[\\s"'])${name}\\s*=\\s*"([^"]*)"`));
  return m ? m[1] : null;
}

// ---- fetch + extract (once) ------------------------------------------------

async function resolveLatest() {
  let res;
  try {
    res = await fetch(REGISTRY_PKG, { headers: { accept: "application/json" } });
  } catch (e) {
    failNetwork(e);
  }
  if (!res.ok) failNetwork(new Error(`registry metadata HTTP ${res.status}`));
  const meta = await res.json();
  const version = meta["dist-tags"] && meta["dist-tags"].latest;
  const tarball =
    meta.versions && meta.versions[version] && meta.versions[version].dist &&
    meta.versions[version].dist.tarball;
  if (!version || !tarball) {
    failNetwork(new Error("registry metadata missing latest/tarball"));
  }
  return { version, tarball };
}

function failNetwork(e) {
  console.error(
    "\n[convert.mjs] NETWORK ERROR: could not reach the npm registry for @tabler/icons.\n" +
      "  Detail: " + (e && e.message ? e.message : String(e)) +
      "\n  This script intentionally does NOT hand-author SVG paths. Restore network and re-run.\n"
  );
  process.exit(1);
}

async function ensurePackage(version, tarballUrl) {
  const cache = path.join(os.tmpdir(), "tabler-convert", version);
  const pkgDir = path.join(cache, "package");
  const marker = path.join(cache, ".extracted.ok");

  // Already fully extracted? reuse without touching the network again.
  if (fs.existsSync(marker) && fs.existsSync(pkgDir)) {
    return { cache, pkgDir, reused: true };
  }

  fs.mkdirSync(cache, { recursive: true });
  const tgz = path.join(cache, "icons-" + version + ".tgz");
  if (!fs.existsSync(tgz)) {
    // Download the tarball (only when not already cached).
    return downloadThenExtract();
  }
  return extractExisting();

  async function downloadThenExtract() {
    let res;
    try {
      res = await fetch(tarballUrl);
    } catch (e) {
      failNetwork(e);
    }
    if (!res.ok) failNetwork(new Error(`tarball HTTP ${res.status}`));
    const buf = Buffer.from(await res.arrayBuffer());
    fs.writeFileSync(tgz, buf);
    doExtract();
    return { cache, pkgDir, reused: false };
  }

  function extractExisting() {
    doExtract();
    return { cache, pkgDir, reused: false };
  }

  function doExtract() {
    fs.rmSync(pkgDir, { recursive: true, force: true });
    try {
      execFileSync("tar", ["-xzf", toMsys(tgz), "-C", toMsys(cache)], {
        stdio: ["ignore", "inherit", "inherit"],
      });
    } catch (e) {
      console.error(
        "\n[convert.mjs] Failed to run system `tar`. This script must run inside Git Bash " +
          "where GNU tar is on PATH.\n  " + (e && e.message ? e.message : String(e)) + "\n"
      );
      process.exit(1);
    }
    if (!fs.existsSync(pkgDir)) {
      console.error("[convert.mjs] Extraction produced no `package/` dir at " + pkgDir);
      process.exit(1);
    }
    fs.writeFileSync(marker, version);
  }
}

function findSvgDir(pkgDir) {
  for (const rel of SVG_CANDIDATE_DIRS) {
    const abs = path.join(pkgDir, rel);
    if (fs.existsSync(abs) && fs.statSync(abs).isDirectory()) return abs;
  }
  console.error(
    "[convert.mjs] Could not locate outlined SVG dir. Tried: " +
      SVG_CANDIDATE_DIRS.map((d) => path.join(pkgDir, d)).join(", ")
  );
  process.exit(1);
}

// ---- core conversion -------------------------------------------------------

function svgToVector(svgText, meta) {
  const svgTag = (svgText.match(/<svg\b[^>]*>/i) || [""])[0];
  if (!svgTag) throw new Error("no <svg> root element");

  const viewBox = (getAttr(svgTag, "viewBox") || "0 0 24 24").trim().split(/[\s,]+/).map(Number);
  const [, , vw, vh] = viewBox.length === 4 ? viewBox : [0, 0, 24, 24];
  const wAttr = getAttr(svgTag, "width") || String(vw);
  const hAttr = getAttr(svgTag, "height") || String(vh);

  const gStrokeWidth = getAttr(svgTag, "stroke-width") || "2";
  const gLineCap = getAttr(svgTag, "stroke-linecap") || "round";
  const gLineJoin = getAttr(svgTag, "stroke-linejoin") || "round";

  const paths = [];
  for (const m of svgText.matchAll(/<path\b([^>]*)>/g)) {
    const attrs = m[1].replace(/\/$/, "");
    const d = getAttr(attrs, "d");
    if (!d) continue; // path without geometry -> nothing to emit

    const stroke = getAttr(attrs, "stroke"); // "none" only on the transparent clip rect
    const fill = getAttr(attrs, "fill"); // "none" everywhere for outlined set

    const invisible = stroke === "none"; // tabler's M0 0h24v24H0z background rect
    const fillColor =
      fill && fill !== "none" && fill !== "transparent" ? STROKE_COLOR : null;

    paths.push({
      d,
      invisible,
      strokeWidth: getAttr(attrs, "stroke-width") || gStrokeWidth,
      strokeLineCap: getAttr(attrs, "stroke-linecap") || gLineCap,
      strokeLineJoin: getAttr(attrs, "stroke-linejoin") || gLineJoin,
      fillColor,
    });
  }
  if (paths.length === 0) throw new Error("no drawable <path> elements");

  const body = paths
    .map((p) => {
      const lines = ["    <path", `        android:pathData="${xmlEscape(p.d)}"`];
      if (p.fillColor) lines.push(`        android:fillColor="${p.fillColor}"`);
      if (!p.invisible) {
        lines.push(`        android:strokeColor="${STROKE_COLOR}"`);
        lines.push(`        android:strokeWidth="${p.strokeWidth}"`);
        lines.push(`        android:strokeLineCap="${p.strokeLineCap}"`);
        lines.push(`        android:strokeLineJoin="${p.strokeLineJoin}"`);
      }
      lines[lines.length - 1] += " />";
      return lines.join("\n");
    })
    .join("\n");

  const offsetX = viewBox[0] || 0;
  const offsetY = viewBox[1] || 0;
  const offsetAttrs =
    offsetX !== 0 || offsetY !== 0
      ? `\n    android:viewportOffsetX="${offsetX}"\n    android:viewportOffsetY="${offsetY}"`
      : "";

  const header =
    `<?xml version="1.0" encoding="utf-8"?>\n` +
    `<!-- Generated by scripts/tabler/convert.mjs from @tabler/icons v${meta.version} (MIT), ` +
    `icon "${meta.tablerName}". Do not edit by hand; edit icons.json and re-run. -->\n`;

  const vector =
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"\n` +
    `    android:width="${wAttr}dp"\n` +
    `    android:height="${hAttr}dp"\n` +
    `    android:viewportWidth="${vw}"\n` +
    `    android:viewportHeight="${vh}"${offsetAttrs}>\n` +
    body +
    `\n</vector>\n`;

  return header + vector;
}

// ---- main ------------------------------------------------------------------

async function main() {
  const map = JSON.parse(fs.readFileSync(ICONS_JSON, "utf8"));
  const entries = Object.entries(map).filter(([k]) => !k.startsWith("_"));
  if (entries.length === 0) {
    console.error("[convert.mjs] icons.json has no mappings");
    process.exit(1);
  }

  const { version, tarball } = await resolveLatest();
  const { pkgDir, reused } = await ensurePackage(version, tarball);
  const svgDir = findSvgDir(pkgDir);
  console.log(`@tabler/icons v${version} | outline dir: ${svgDir} | tarball ${reused ? "reused from cache" : "downloaded fresh"}`);

  fs.mkdirSync(OUT_DIR, { recursive: true });

  const report = [];
  const problems = [];
  for (const [drawableName, rawVal] of entries) {
    const tablerName = typeof rawVal === "string" ? rawVal : rawVal.icon;
    const note = typeof rawVal === "object" && rawVal.note ? rawVal.note : null;

    // drawable name must equal ic_<tabler with '-' -> '_'> unless a note documents a substitution.
    const expected = "ic_" + tablerName.replace(/-/g, "_");
    if (expected !== drawableName) {
      problems.push(`key "${drawableName}" -> tabler "${tablerName}" expected file "${expected}.xml"`);
    }

    const srcFile = path.join(svgDir, tablerName + ".svg");
    if (!fs.existsSync(srcFile)) {
      problems.push(`missing source SVG for "${tablerName}" (${srcFile})`);
      continue;
    }

    let xml;
    let pathCount;
    try {
      const svgText = fs.readFileSync(srcFile, "utf8");
      pathCount = (svgText.match(/<path\b/g) || []).length;
      xml = svgToVector(svgText, { version, tablerName });
    } catch (e) {
      problems.push(`convert failed for "${tablerName}": ${e.message}`);
      continue;
    }

    const outFile = path.join(OUT_DIR, drawableName + ".xml");
    fs.writeFileSync(outFile, xml, "utf8");
    const outPathCount = (xml.match(/<path\b/g) || []).length;
    report.push({
      drawable: drawableName,
      tabler: tablerName,
      note,
      file: path.relative(REPO_ROOT, outFile).replace(/\\/g, "/"),
      srcPaths: pathCount,
      outPaths: outPathCount,
      ok: pathCount === outPathCount,
    });
  }

  console.log("\nWrote " + report.length + " VectorDrawable file(s) to " + path.relative(".", OUT_DIR));
  for (const r of report) {
    console.log(
      `  ${r.ok ? "OK " : "!! "} ${r.file}  (tabler:${r.tabler} paths ${r.outPaths}/${r.srcPaths})` +
        (r.note ? `  note:${r.note}` : "")
    );
  }

  if (problems.length) {
    console.error("\nPROBLEMS:");
    for (const p of problems) console.error("  - " + p);
    process.exit(1);
  }
  const mismatch = report.filter((r) => !r.ok);
  if (mismatch.length) {
    console.error("\nPath-count mismatch in: " + mismatch.map((m) => m.drawable).join(", "));
    process.exit(1);
  }
  console.log("\nAll " + report.length + " icons converted, path counts match source 1:1.");
}

main().catch((e) => {
  console.error(e && e.stack ? e.stack : String(e));
  process.exit(1);
});
