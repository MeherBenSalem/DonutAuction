/**
 * Upload one Paper/Folia plugin jar as a single Modrinth version and a single
 * CurseForge file, tagged for every Minecraft version in
 * release/supported-minecraft.json.
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { hashes, verifyModrinth, verifyCurseForge, verifyCurseForgeProject } from "./release-helpers.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const support = JSON.parse(
  fs.readFileSync(path.join(root, "release", "supported-minecraft.json"), "utf8"),
);
const gameVersions = support.game_versions;
const loaders = support.loaders;

const version = process.env.VERSION;
const platforms = (process.env.PLATFORMS || "both").toLowerCase();
const doModrinth = platforms === "both" || platforms === "modrinth";
const doCurse = platforms === "both" || platforms === "curseforge";
const jar = process.env.JAR_PATH;

if (!version) throw new Error("VERSION is required");
if (!jar || !fs.existsSync(jar)) throw new Error("JAR_PATH missing or not found: " + jar);

function sectionForVersion(markdown, ver) {
  const lines = markdown.split(/\r?\n/);
  const start = lines.findIndex((line) => {
    const t = line.trim();
    return t === `## ${ver}` || t === `# ${ver}` || t.startsWith(`## ${ver} `) || t.startsWith(`# ${ver} `);
  });
  if (start < 0) return null;
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((line) => /^##\s/.test(line.trim()));
  const body = (end < 0 ? rest : rest.slice(0, end)).join("\n").trim();
  return body || null;
}

function resolveChangelog(ver) {
  const named = fs.readdirSync(root).filter(
    (f) =>
      f.toLowerCase().endsWith(`-${ver}-patchnotes.md`) ||
      f.toLowerCase() === `${ver}-patchnotes.md`,
  );
  for (const f of named) {
    const p = path.join(root, f);
    if (fs.existsSync(p)) return fs.readFileSync(p, "utf8");
  }
  for (const f of ["CHANGELOG.md", "PATCH_NOTES.md"]) {
    const p = path.join(root, f);
    if (!fs.existsSync(p)) continue;
    const full = fs.readFileSync(p, "utf8");
    return sectionForVersion(full, ver) || full;
  }
  return `Release ${ver}`;
}

const changelog = resolveChangelog(version);
const jarName = path.basename(jar);
const digest = hashes(fs.readFileSync(jar));
if (!["both", "modrinth", "curseforge"].includes(platforms)) throw new Error("Invalid PLATFORMS");
if (jarName !== `DonutAuctionHouse-${version}.jar`) throw new Error("Artifact filename/version mismatch");

async function json(url, headers = {}) {
  const response = await fetch(url, { headers });
  if (!response.ok) throw new Error(`Preflight/read failed (${response.status}) for ${url}`);
  return response.json();
}

const mrHeaders = { Authorization: process.env.MODRINTH_TOKEN || "", "User-Agent": "NightBeam-DonutAuctionHouse-release" };
const cfHeaders = { "x-api-key": process.env.CURSEFORGE_API_KEY || "" };

(async () => {
  // Validate both existing destinations before the first upload. No listings are created.
  let existingMr;
  let existingCf;
  if (doModrinth) {
    if (!process.env.MODRINTH_TOKEN || process.env.MODRINTH_ID !== "8XgyeSRH") throw new Error("Missing Modrinth authentication or unexpected project ID");
    const project = await json(`https://api.modrinth.com/v2/project/${process.env.MODRINTH_ID}`, mrHeaders);
    if (project.id !== "8XgyeSRH" || project.source_url !== "https://github.com/MeherBenSalem/DonutAuction") throw new Error("Modrinth project identity mismatch");
    const team = await json(`https://api.modrinth.com/v2/team/${project.team}/members`, mrHeaders);
    const user = await json("https://api.modrinth.com/v2/user", mrHeaders);
    if (!team.some(member => member.user.id === user.id)) throw new Error("Authenticated Modrinth user is not on the project team");
    const availableVersions = await json("https://api.modrinth.com/v2/tag/game_version", mrHeaders);
    const availableLoaders = await json("https://api.modrinth.com/v2/tag/loader", mrHeaders);
    if (!gameVersions.every(version => availableVersions.some(tag => tag.version === version))
        || !loaders.every(loader => availableLoaders.some(tag => tag.name === loader))) throw new Error("Modrinth cannot tag the full supported version/loader matrix");
    const versions = await json(`https://api.modrinth.com/v2/project/${project.id}/version`, mrHeaders);
    existingMr = versions.find(entry => entry.version_number === version);
    if (existingMr) verifyModrinth(existingMr, project.id, version, support, digest);
    console.log("Modrinth preflight", project.id, project.slug, user.username, existingMr ? "already uploaded" : "new version");
  }
  if (doCurse) {
    if (!process.env.CURSEFORGE_TOKEN || !process.env.CURSEFORGE_API_KEY || process.env.CURSEFORGE_ID !== "1479926") throw new Error("Missing CurseForge authentication or unexpected project ID");
    const project = (await json(`https://api.curseforge.com/v1/mods/${process.env.CURSEFORGE_ID}`, cfHeaders)).data;
    console.log("CurseForge target", project.id, project.name, project.slug, project.authors.map(author => author.name).join(","));
    verifyCurseForgeProject(project);
    const availableVersions = (await json("https://api.curseforge.com/v1/games/432/versions", cfHeaders)).data.flatMap(type => type.versions);
    if (![...gameVersions, "Client", "Server"].every(version => availableVersions.includes(version))) throw new Error("CurseForge cannot tag the full supported version/environment matrix");
    console.log("CurseForge loader tag availability", loaders.map(loader => `${loader}:${availableVersions.some(tag => tag.toLowerCase() === loader)}`).join(" "));
    const files = (await json(`https://api.curseforge.com/v1/mods/${project.id}/files?pageSize=50`, cfHeaders)).data;
    existingCf = files.find(entry => entry.fileName === jarName || entry.displayName === version);
    const recovery = path.join(process.env.RECOVERY_RECEIPTS || "recovery-receipts", "curseforge-upload-receipt.json");
    if (!existingCf && fs.existsSync(recovery)) {
      const prior = JSON.parse(fs.readFileSync(recovery, "utf8"));
      if (prior.project_id !== process.env.CURSEFORGE_ID || prior.version !== version || prior.sha256 !== digest.sha256) throw new Error("Recovery receipt belongs to a different release");
      const acceptedId = prior.upload.id ?? prior.upload.data?.id;
      console.log("Previously accepted CurseForge file", acceptedId, "- verifying; no duplicate upload will be attempted");
      existingCf = (await json(`https://api.curseforge.com/v1/mods/${project.id}/files/${acceptedId}`, cfHeaders)).data;
    }
    if (existingCf) verifyCurseForge(existingCf, process.env.CURSEFORGE_ID, version, support, digest);
    console.log("CurseForge preflight", project.id, project.slug, project.authors.map(author => author.name).join(","), existingCf ? "already uploaded" : "new version");
  }
  console.log("Artifact SHA256", digest.sha256);
  if (process.env.PREFLIGHT_ONLY === "true") return;
  if (doModrinth && !existingMr) {
    if (!process.env.MODRINTH_TOKEN || !process.env.MODRINTH_ID) {
      throw new Error("MODRINTH_TOKEN and MODRINTH_ID are required");
    }
    const body = {
      name: version,
      version_number: version,
      changelog,
      dependencies: [],
      game_versions: gameVersions,
      version_type: "release",
      loaders,
      featured: true,
      status: "listed",
      project_id: process.env.MODRINTH_ID,
      file_parts: ["file_0"],
      primary_file: "file_0",
    };
    const form = new FormData();
    form.append("data", JSON.stringify(body));
    form.append("file_0", new Blob([fs.readFileSync(jar)]), jarName);
    const mrRes = await fetch("https://api.modrinth.com/v2/version", {
      method: "POST",
      headers: { Authorization: process.env.MODRINTH_TOKEN },
      body: form,
    });
    const mrText = await mrRes.text();
    if (!mrRes.ok) throw new Error("Modrinth " + mrRes.status + " " + mrText.slice(0, 500));
    const receipt = JSON.parse(mrText);
    verifyModrinth(receipt, process.env.MODRINTH_ID, version, support, digest);
    fs.writeFileSync("modrinth-receipt.json", JSON.stringify(receipt, null, 2));
    console.log("Modrinth OK", receipt.id, version, loaders.join("+"), gameVersions.length, "MC versions");
  } else {
    console.log("Skipping Modrinth");
  }

  if (!doCurse || existingCf) {
    console.log("Skipping CurseForge");
    return;
  }
  if (!process.env.CURSEFORGE_TOKEN || !process.env.CURSEFORGE_ID) {
    throw new Error("CURSEFORGE_TOKEN and CURSEFORGE_ID are required");
  }

  const gameVersionNames = [...gameVersions, "Client", "Server"];
  const meta = {
    changelog,
    changelogType: "markdown",
    displayName: version,
    gameVersionNames,
    releaseType: "release",
  };
  const cfForm = new FormData();
  cfForm.append("metadata", JSON.stringify(meta));
  cfForm.append("file", new Blob([fs.readFileSync(jar)]), jarName);
  const cfRes = await fetch(
    `https://minecraft.curseforge.com/api/projects/${process.env.CURSEFORGE_ID}/upload-file`,
    { method: "POST", headers: { "X-Api-Token": process.env.CURSEFORGE_TOKEN }, body: cfForm },
  );
  const cfText = await cfRes.text();
  if (!cfRes.ok) throw new Error("CurseForge " + cfRes.status + " " + cfText.slice(0, 500));
  const uploaded = JSON.parse(cfText);
  fs.writeFileSync("curseforge-upload-receipt.json", JSON.stringify({ project_id: process.env.CURSEFORGE_ID, version, sha256: digest.sha256, upload: uploaded }, null, 2));
  const fileId = uploaded.id ?? uploaded.data?.id;
  if (!fileId) throw new Error("CurseForge upload succeeded but returned no file ID; inspect receipt before retrying");
  console.log("CurseForge upload accepted", process.env.CURSEFORGE_ID, fileId, version, jarName);
  const receipt = (await json(`https://api.curseforge.com/v1/mods/${process.env.CURSEFORGE_ID}/files/${fileId}`, cfHeaders)).data;
  verifyCurseForge(receipt, process.env.CURSEFORGE_ID, version, support, digest);
  fs.writeFileSync("curseforge-receipt.json", JSON.stringify(receipt, null, 2));
  console.log("CurseForge verified", receipt.id, "status", receipt.fileStatus, "SHA1", digest.sha1);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
