import { test } from "node:test";
import assert from "node:assert/strict";
import { hashes, sameSet, verifyModrinth, verifyCurseForge } from "./release-helpers.mjs";

const support = { loaders: ["paper", "folia"], game_versions: ["1.20.1", "26.3"] };
const digest = hashes(Buffer.from("immutable release bytes"));
const mr = { project_id: "8XgyeSRH", version_number: "1.6.0", version_type: "release", status: "listed",
  loaders: support.loaders, game_versions: support.game_versions, dependencies: [],
  files: [{ primary: true, filename: "DonutAuctionHouse-1.6.0.jar", hashes: digest }] };
const cf = { modId: 1479926, fileName: "DonutAuctionHouse-1.6.0.jar", displayName: "1.6.0", releaseType: 1,
  gameVersions: support.game_versions, hashes: [{ algo: 1, value: digest.sha1 }] };

test("receipts must match bytes, version, targets and full metadata", () => {
  verifyModrinth(mr, "8XgyeSRH", "1.6.0", support, digest);
  verifyCurseForge(cf, "1479926", "1.6.0", support, digest);
  assert.throws(() => verifyModrinth({ ...mr, loaders: ["paper"] }, "8XgyeSRH", "1.6.0", support, digest));
  assert.throws(() => verifyModrinth(mr, "wrong", "1.6.0", support, digest));
  assert.throws(() => verifyModrinth(mr, "8XgyeSRH", "1.6.0", support, hashes(Buffer.from("changed"))));
  assert.throws(() => verifyCurseForge({ ...cf, gameVersions: ["1.20.1"] }, "1479926", "1.6.0", support, digest));
  assert.throws(() => verifyCurseForge(cf, "1479926", "wrong", support, digest));
  assert.throws(() => verifyCurseForge(cf, "1479926", "1.6.0", support, hashes(Buffer.from("changed"))));
});

test("sets and hashes detect changed artifacts", () => {
  assert.equal(sameSet(["paper", "folia"], ["folia", "paper"]), true);
  assert.equal(sameSet(["paper"], ["folia", "paper"]), false);
  assert.notEqual(digest.sha512, hashes(Buffer.from("changed")).sha512);
});
