import { readFileSync } from "node:fs";
import { isIP } from "node:net";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function validPort(value: string | undefined): number | null {
  if (!value || !/^\d{1,5}$/.test(value.trim())) return null;
  const port = Number(value.trim());
  return Number.isInteger(port) && port > 0 && port <= 65_535 ? port : null;
}

function serverPortFromProjectEnv(): number | null {
  try {
    const assignment = readFileSync(resolve(projectRoot, ".env"), "utf8")
      .split(/\r?\n/)
      .find((line) => /^\s*EAST_SERVER_PORT\s*=/.test(line));
    if (!assignment) return null;

    const match = /^\s*EAST_SERVER_PORT\s*=\s*(?:"(\d+)"|'(\d+)'|(\d+))\s*(?:#.*)?$/.exec(assignment);
    return validPort(match?.[1] ?? match?.[2] ?? match?.[3]);
  } catch {
    return null;
  }
}

const apiTargetOverride = process.env.EAST_API_TARGET?.trim();
const backendPort = validPort(process.env.EAST_SERVER_PORT) ?? serverPortFromProjectEnv() ?? 8080;

function loopbackTargetFromOverride(value: string): string {
  let target: URL;
  try {
    target = new URL(value);
  } catch {
    throw new Error("EAST_API_TARGET must be an HTTP URL pointing to localhost or a loopback IP.");
  }

  const hostname = target.hostname.replace(/^\[|\]$/g, "").replace(/\.$/, "").toLowerCase();
  const ipVersion = isIP(hostname);
  const isLoopback = hostname === "localhost"
    || (ipVersion === 4 && hostname.split(".")[0] === "127")
    || (ipVersion === 6 && hostname === "::1");
  if (target.protocol !== "http:"
    || !isLoopback
    || target.username !== ""
    || target.password !== ""
    || target.pathname !== "/"
    || target.search !== ""
    || target.hash !== "") {
    throw new Error("EAST_API_TARGET must be a plain HTTP URL on localhost or a loopback IP, without credentials, path, query, or fragment.");
  }

  return target.origin;
}

const eastApiTarget = apiTargetOverride
  ? loopbackTargetFromOverride(apiTargetOverride)
  : `http://127.0.0.1:${backendPort}`;

function createApiProxy() {
  return {
    "/api": {
      target: eastApiTarget,
      // Keep Vite's proxy aligned with Spring Boot's IPv4 loopback bind.
      changeOrigin: true,
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Bind only the IPv4 loopback interface; use the documented `localhost`
    // URL in the browser so it matches the Google OAuth host and cookies.
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: createApiProxy(),
  },
  preview: {
    host: "127.0.0.1",
    proxy: createApiProxy(),
  },
});
