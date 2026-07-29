import { cp, mkdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const feRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const standaloneRoot = join(feRoot, ".next", "standalone");

await cp(join(feRoot, "public"), join(standaloneRoot, "public"), {
  recursive: true,
  force: true,
});
await mkdir(join(standaloneRoot, ".next"), { recursive: true });
await cp(join(feRoot, ".next", "static"), join(standaloneRoot, ".next", "static"), {
  recursive: true,
  force: true,
});
await import(pathToFileURL(join(standaloneRoot, "server.js")).href);
