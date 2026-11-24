import crypto from "node:crypto";

export function hashes(bytes) {
  return Object.fromEntries(["sha1", "sha512", "sha256"].map(name => [name, crypto.createHash(name).update(bytes).digest("hex")]));
}

export function sameSet(actual, expected) {
  return Array.isArray(actual) && actual.length === expected.length && expected.every(value => actual.includes(value));
}

export function verifyCurseForgeProject(project) {
  if (project.id !== 1479926 || project.slug !== "donut-auction" || project.name !== "Donut Auction"
      || !project.authors?.some(author => author.name === "NightBeamStudio")) {
    throw new Error("CurseForge project identity/owner mismatch");
  }
}

export function verifyCurseForge(receipt, project, version, support, digest) {
  if (receipt.modId !== Number(project) || receipt.fileName !== `DonutAuctionHouse-${version}.jar`
      || receipt.displayName !== version || receipt.releaseType !== 1
      || !support.game_versions.every(value => receipt.gameVersions?.includes(value))
      || !receipt.hashes?.some(hash => hash.algo === 1 && hash.value.toLowerCase() === digest.sha1)) {
    throw new Error("CurseForge receipt does not match the immutable release artifact and metadata");
  }
}
