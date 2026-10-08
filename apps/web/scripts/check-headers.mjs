// Fails when a Render blueprint's response headers for the web app differ
// from security-headers.json (the copy vite preview serves to e2e tests).
import { readFileSync } from "node:fs";

const headers = JSON.parse(readFileSync(new URL("../security-headers.json", import.meta.url), "utf8"));
delete headers["//"];

let failed = false;
for (const blueprint of ["../../../render.yaml", "../../../deploy/render-ha.yaml"]) {
  const text = readFileSync(new URL(blueprint, import.meta.url), "utf8");
  for (const [name, value] of Object.entries(headers)) {
    const expected = `name: ${name}\n        value: ${JSON.stringify(value)}`;
    if (!text.includes(expected)) {
      console.error(`${blueprint.replace(/^(\.\.\/)+/, "")}: header ${name} is missing or differs from security-headers.json`);
      failed = true;
    }
  }
}
if (failed) process.exit(1);
console.log("Render headers match security-headers.json");
