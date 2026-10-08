import { build } from "esbuild";
import { readFile, writeFile } from "node:fs/promises";
await build({
  entryPoints: ["ui/yaml-editor.js"],
  bundle: true,
  minify: true,
  format: "esm",
  target: "es2022",
  outfile: "src/main/resources/static/vendor/yaml-editor.js",
  metafile: true,
}).then(async (result) => {
  const packages = new Set(
    Object.keys(result.metafile.inputs)
      .filter((path) => path.startsWith("node_modules/"))
      .map((path) => {
        const parts = path.split("/");
        return parts[1].startsWith("@") ? parts.slice(1, 3).join("/") : parts[1];
      }),
  );
  const licenses = [];
  for (const name of [...packages].sort()) {
    const info = JSON.parse(await readFile(`node_modules/${name}/package.json`, "utf8"));
    let license = "";
    for (const file of ["LICENSE", "LICENSE.md", "LICENSE.txt"]) {
      try {
        license = await readFile(`node_modules/${name}/${file}`, "utf8");
        break;
      } catch {}
    }
    if (!license) throw new Error(`Missing license for ${name}`);
    licenses.push(`${name} ${info.version}\n${license}`);
  }
  await writeFile("src/main/resources/static/vendor/LICENSES.txt", licenses.join("\n\n"));
});
